package org.vb.chatbot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ChatClient extends JFrame {
    private final JTextArea chatArea = new JTextArea();
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JComboBox<String> roomBox = new JComboBox<>();
    private final JButton createButton = new JButton("Create Room");
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JLabel status = new JLabel("Connecting...");
    private Socket socket;
    private PrintWriter writer;
    private volatile boolean connected;
    private boolean updatingRooms;
    private String currentRoom = "Global Room";

    public ChatClient(String host, int port, String username) {
        super("Java Chat - " + username);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 520);
        setLocationByPlatform(true);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel("Room:"), BorderLayout.WEST);
        top.add(roomBox, BorderLayout.CENTER);
        top.add(createButton, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JList<String> userList = new JList<>(userModel);
        JScrollPane usersPane = new JScrollPane(userList);
        usersPane.setPreferredSize(new Dimension(160, 0));
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.add(new JLabel(" Users in room"), BorderLayout.NORTH);
        usersPanel.add(usersPane, BorderLayout.CENTER);
        add(usersPanel, BorderLayout.EAST);

        JPanel compose = new JPanel(new BorderLayout(8, 0));
        compose.add(messageField, BorderLayout.CENTER);
        compose.add(sendButton, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(compose, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        createButton.addActionListener(e -> createRoom());
        roomBox.addActionListener(e -> {
            if (updatingRooms || !connected) return;
            String selected = (String) roomBox.getSelectedItem();
            if (selected != null && !selected.equals(currentRoom)) send("JOIN|" + selected);
        });
        setControlsEnabled(false);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); }
        });
        new Thread(() -> connect(host, port, username), "chat-network").start();
    }

    private void connect(String host, int port, String username) {
        try {
            socket = new Socket(host, port);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            send(username);
            String line;
            while ((line = input.readLine()) != null) {
                if (line.startsWith("WELCOME|")) {
                    connected = true;
                    SwingUtilities.invokeLater(() -> {
                        status.setText("Connected to " + host + ":" + port);
                        setControlsEnabled(true);
                        messageField.requestFocusInWindow();
                    });
                } else if (line.startsWith("ERROR|")) {
                    append("[Error] " + line.substring(6));
                    // Nonfatal room errors do not disconnect the client.
                    if (!connected) break;
                } else if (line.startsWith("ROOMS|")) {
                    String names = line.substring(6);
                    SwingUtilities.invokeLater(() -> {
                        updatingRooms = true;
                        roomBox.removeAllItems();
                        if (!names.isEmpty()) for (String name : names.split(",")) roomBox.addItem(name);
                        roomBox.setSelectedItem(currentRoom);
                        updatingRooms = false;
                    });
                } else if (line.startsWith("ROOM|")) {
                    String joinedRoom = line.substring(5);
                    SwingUtilities.invokeLater(() -> {
                        currentRoom = joinedRoom;
                        chatArea.setText("");
                        userModel.clear();
                        updatingRooms = true;
                        roomBox.setSelectedItem(joinedRoom);
                        updatingRooms = false;
                        setTitle("Java Chat - " + username + " - " + joinedRoom);
                        append("[Server] You are now in " + joinedRoom);
                    });
                } else if (line.startsWith("USERS|")) {
                    String names = line.substring(6);
                    SwingUtilities.invokeLater(() -> {
                        userModel.clear();
                        if (!names.isEmpty()) for (String name : names.split(",")) userModel.addElement(name);
                    });
                } else if (line.startsWith("SYSTEM|")) {
                    append("[Server] " + line.substring(7));
                } else if (line.startsWith("CHAT|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) append(parts[1] + ": " + parts[2]);
                }
            }
        } catch (IOException e) {
            append("[Error] " + e.getMessage());
        } finally {
            connected = false;
            SwingUtilities.invokeLater(() -> {
                setControlsEnabled(false);
                status.setText("Disconnected");
                userModel.clear();
            });
            disconnect();
        }
    }

    private void createRoom() {
        String name = JOptionPane.showInputDialog(this, "New room name:", "Create Room", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (!name.matches("[A-Za-z0-9 _-]{1,30}") || name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Use 1-30 letters, digits, spaces, _ or -.");
            return;
        }
        send("CREATE|" + name);
    }

    private void sendMessage() {
        String message = messageField.getText().trim();
        if (!connected || message.isEmpty()) return;
        if (message.length() > 1000) {
            JOptionPane.showMessageDialog(this, "Maximum message length: 1000 characters.");
            return;
        }
        send("MSG|" + message);
        messageField.setText("");
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(text + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void send(String line) {
        PrintWriter out = writer;
        if (out != null) out.println(line);
    }

    private void setControlsEnabled(boolean enabled) {
        messageField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        roomBox.setEnabled(enabled);
        createButton.setEnabled(enabled);
    }

    private void disconnect() {
        if (connected) send("QUIT");
        connected = false;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String host = JOptionPane.showInputDialog(null, "Server address:", "localhost");
            if (host == null) return;
            if (host.isBlank()) host = "localhost";
            String username = JOptionPane.showInputDialog(null, "Username (letters, digits, underscores):");
            if (username == null) return;
            username = username.trim();
            if (!username.matches("[A-Za-z0-9_]{1,20}")) {
                JOptionPane.showMessageDialog(null, "Invalid username (1-20 letters, digits, underscores).");
                return;
            }
            new ChatClient(host.trim(), 5000, username).setVisible(true);
        });
    }
}
