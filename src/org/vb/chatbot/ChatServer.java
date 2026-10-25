package org.vb.chatbot;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 8081;
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Chat server listening on port " + PORT);
            while (true) {
                Socket socket = server.accept();
                Thread thread = new Thread(new ClientHandler(socket), "client-" + socket.getPort());
                thread.start();
            }
        }
    }

    private static void broadcast(String message) {
        for (ClientHandler client : clients.values()) client.send(message);
        System.out.println(message);
    }

    private static void updateUsers() {
        List<String> names = new ArrayList<>(clients.keySet());
        Collections.sort(names);
        broadcast("USERS|" + String.join(",", names));
    }

    private static final class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter writer;
        private String username;

        ClientHandler(Socket socket) { this.socket = socket; }

        void send(String message) {
            if (writer != null) writer.println(message);
        }

        @Override public void run() {
            try (Socket connection = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8), true)) {
                writer = out;
                String requestedName = reader.readLine();
                if (requestedName == null) return;
                requestedName = requestedName.trim();
                if (!requestedName.matches("[A-Za-z0-9_]{1,20}")) {
                    send("ERROR|Username must be 1-20 letters, digits, or underscores.");
                    return;
                }
                synchronized (LOCK) {
                    if (clients.putIfAbsent(requestedName, this) != null) {
                        send("ERROR|That username is already in use.");
                        return;
                    }
                    username = requestedName;
                }
                send("WELCOME|" + username);
                broadcast("SYSTEM|" + username + " joined the chat.");
                updateUsers();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("QUIT")) break;
                    if (line.startsWith("MSG|")) {
                        String message = line.substring(4).trim().replace('\n', ' ').replace('\r', ' ');
                        if (!message.isEmpty()) {
                            if (message.length() > 1000) message = message.substring(0, 1000);
                            broadcast("CHAT|" + username + "|" + message);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Connection closed: " + e.getMessage());
            } finally {
                if (username != null && clients.remove(username, this)) {
                    broadcast("SYSTEM|" + username + " left the chat.");
                    updateUsers();
                }
            }
        }
    }
}


