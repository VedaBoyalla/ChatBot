package org.vb.chatbot;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ChatServer {
    private static final int PORT = 5000;
    private static final String DEFAULT_ROOM = "Global Room";
    private static final Object LOCK = new Object();
    private static final Map<String, ClientHandler> clients = new HashMap<>();
    private static final Map<String, Set<ClientHandler>> rooms = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        synchronized (LOCK) { rooms.put(DEFAULT_ROOM, new HashSet<>()); }
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Chat server listening on port " + PORT);
            while (true) {
                Socket socket = server.accept();
                new Thread(new ClientHandler(socket), "client-" + socket.getPort()).start();
            }
        }
    }

    // Call these helpers while holding LOCK. Send is nonblocking for typical local clients.
    private static void roomMessage(String room, String line) {
        for (ClientHandler client : rooms.get(room)) client.send(line);
    }

    private static void updateRooms() {
        String message = "ROOMS|" + String.join(",", rooms.keySet());
        for (ClientHandler client : clients.values()) client.send(message);
    }

    private static void updateUsers(String room) {
        List<String> names = new ArrayList<>();
        for (ClientHandler client : rooms.get(room)) names.add(client.username);
        Collections.sort(names);
        roomMessage(room, "USERS|" + String.join(",", names));
    }

    private static boolean validRoom(String value) {
        return value != null && value.matches("[A-Za-z0-9 _-]{1,30}") && !value.isBlank();
    }

    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter writer;
        private String username;
        private String room;

        ClientHandler(Socket socket) { this.socket = socket; }

        void send(String message) {
            if (writer != null) writer.println(message);
        }

        private void joinRoom(String target) {
            synchronized (LOCK) {
                if (!rooms.containsKey(target)) {
                    send("ERROR|That room does not exist.");
                    return;
                }
                if (target.equals(room)) return;
                String previous = room;
                if (previous != null) {
                    rooms.get(previous).remove(this);
                    roomMessage(previous, "SYSTEM|" + username + " left the room.");
                    updateUsers(previous);
                }
                room = target;
                rooms.get(target).add(this);
                send("ROOM|" + target);
                roomMessage(target, "SYSTEM|" + username + " joined the room.");
                updateUsers(target);
            }
        }

        @Override public void run() {
            try (Socket connection = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8), true)) {
                writer = out;
                String name = reader.readLine();
                if (name == null) return;
                name = name.trim();
                if (!name.matches("[A-Za-z0-9_]{1,20}")) {
                    send("ERROR|Username must contain 1-20 letters, digits or underscores.");
                    return;
                }
                synchronized (LOCK) {
                    if (clients.containsKey(name)) {
                        send("ERROR|Username is already taken.");
                        return;
                    }
                    username = name;
                    clients.put(username, this);
                    send("WELCOME|" + username);
                    updateRooms();
                }
                joinRoom(DEFAULT_ROOM);

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("QUIT")) break;
                    if (line.startsWith("MSG|")) {
                        String msg = line.substring(4).trim();
                        if (!msg.isEmpty() && msg.length() <= 1000) {
                            synchronized (LOCK) {
                                if (room != null) roomMessage(room, "CHAT|" + username + "|" + msg);
                            }
                        }
                    } else if (line.startsWith("JOIN|")) {
                        joinRoom(line.substring(5));
                    } else if (line.startsWith("CREATE|")) {
                        String newRoom = line.substring(7).trim();
                        if (!validRoom(newRoom)) {
                            send("ERROR|Room name must be 1-30 letters, digits, spaces, _ or -.");
                            continue;
                        }
                        synchronized (LOCK) {
                            if (rooms.containsKey(newRoom)) {
                                send("ERROR|Room already exists. Select it from the room list.");
                                continue;
                            }
                            rooms.put(newRoom, new HashSet<>());
                            updateRooms();
                        }
                        joinRoom(newRoom);
                    }
                }
            } catch (IOException e) {
                System.out.println("Client connection ended: " + e.getMessage());
            } finally {
                synchronized (LOCK) {
                    if (username != null && clients.get(username) == this) {
                        clients.remove(username);
                        if (room != null) {
                            rooms.get(room).remove(this);
                            roomMessage(room, "SYSTEM|" + username + " disconnected.");
                            updateUsers(room);
                        }
                    }
                }
            }
        }
    }
}
