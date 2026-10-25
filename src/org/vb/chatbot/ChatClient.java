package org.vb.chatbot;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ChatClient extends JFrame {
    private final JTextArea chatArea = new JTextArea();
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JLabel statusLabel = new JLabel("Connecting...");
    private Socket socket;
    private PrintWriter writer;
    private volatile boolean connected;

    public ChatClient(String host, int port, String username) {
        super("Java Chat - " + username);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(730, 480);
        setLocationByPlatform(true);

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        JScrollPane chatScroll = new JScrollPane(chatArea);

        JList<String> users = new JList<>(userModel);
        JScrollPane userScroll = new JScrollPane(users);
        userScroll.setPreferredSize(new Dimension(160, 0));
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.add(new JLabel(" Online users"), BorderLayout.NORTH);
        usersPanel.add(userScroll, BorderLayout.CENTER);

        JPanel messagePanel = new JPanel(new BorderLayout(8, 0));
        messagePanel.add(inputField, BorderLayout.CENTER);
        messagePanel.add(sendButton, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(statusLabel, BorderLayout.NORTH);
        bottom.add(messagePanel, BorderLayout.CENTER);

        add(chatScroll, BorderLayout.CENTER);
        add(usersPanel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        setControlsEnabled(false);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); }
        });

        new Thread(() -> connect(host, port, username), "server-reader").start();
    }

    private void connect(String host, int port, String username) {
        try {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(username);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("WELCOME|")) {
                    connected = true;
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Connected to " + host + ":" + port);
                        setControlsEnabled(true);
                        inputField.requestFocusInWindow();
                    });
                } else if (line.startsWith("ERROR|")) {
                    append("Error: " + line.substring(6));
                    break;
                } else if (line.startsWith("SYSTEM|")) {
                    append("[Server] " + line.substring(7));
                } else if (line.startsWith("CHAT|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) append(parts[1] + ": " + parts[2]);
                } else if (line.startsWith("USERS|")) {
                    String names = line.substring(6);
                    SwingUtilities.invokeLater(() -> {
                        userModel.clear();
                        if (!names.isEmpty()) {
                            for (String name : names.split(",")) userModel.addElement(name);
                        }
                    });
                }
            }
        } catch (IOException e) {
            append("Connection error: " + e.getMessage());
        } finally {
            connected = false;
            SwingUtilities.invokeLater(() -> {
                setControlsEnabled(false);
                statusLabel.setText("Disconnected");
                userModel.clear();
            });
            disconnect();
        }
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!connected || text.isEmpty()) return;
        if (text.length() > 1000) {
            JOptionPane.showMessageDialog(this, "Maximum message length is 1000 characters.");
            return;
        }
        writer.println("MSG|" + text);
        inputField.setText("");
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(text + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void setControlsEnabled(boolean enabled) {
        inputField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }

    private void disconnect() {
        if (writer != null && connected) writer.println("QUIT");
        connected = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String host = JOptionPane.showInputDialog(null, "Server host:", "localhost");
            if (host == null) return;
            host = host.trim();
            if (host.isEmpty()) host = "localhost";
            String username = JOptionPane.showInputDialog(null, "Choose username (letters, digits, underscores):");
            if (username == null) return;
            username = username.trim();
            if (!username.matches("[A-Za-z0-9_]{1,20}")) {
                JOptionPane.showMessageDialog(null, "Username must be 1-20 letters, digits, or underscores.");
                return;
            }
            new ChatClient(host, 8081, username).setVisible(true);
        });
    }
}
