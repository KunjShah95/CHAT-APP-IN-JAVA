import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.text.*;

public class ChatClient extends JFrame {
    private final JTextPane messageArea;
    private JTextField textField;
    private final JList<String> userList;
    private final DefaultListModel<String> userListModel;
    private final JLabel typingLabel;
    private final JTextPane pinnedArea;
    private PrintWriter writer;
    private BufferedReader reader;
    private Socket socket;
    private String username;
    private final Map<String, Color> userColors = new HashMap<>();
    private final String sessionKey = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes()).substring(0, 16); // 16-byte key
    private final JComboBox<String> themeCombo;
    private final Map<String, String> messageIds = new HashMap<>(); // Text -> ID for editing/deleting

    public ChatClient() {
        super("Chat Application");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(700, 500);
        setLayout(new BorderLayout(5, 5));

        // Pinned Messages Area
        pinnedArea = new JTextPane();
        pinnedArea.setEditable(false);
        pinnedArea.setPreferredSize(new Dimension(0, 50));
        add(new JScrollPane(pinnedArea), BorderLayout.NORTH);

        // Main Message Area
        messageArea = new JTextPane();
        messageArea.setEditable(false);
        messageArea.setFont(new Font("Arial", Font.PLAIN, 14));
        messageArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showMessageContextMenu(e.getX(), e.getY());
                }
            }
        });
        add(new JScrollPane(messageArea), BorderLayout.CENTER);

        // User List and Theme Selector
        JPanel eastPanel = new JPanel(new BorderLayout());
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setPreferredSize(new Dimension(150, 0));
        eastPanel.add(new JScrollPane(userList), BorderLayout.CENTER);

        themeCombo = new JComboBox<>(new String[]{"Light", "Dark", "Blue"});
        themeCombo.addActionListener(e -> applyTheme());
        eastPanel.add(themeCombo, BorderLayout.NORTH);
        add(eastPanel, BorderLayout.EAST);

        // Input Panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        textField = new JTextField();
        textField.addActionListener(e -> sendMessage());
        textField.addKeyListener(new KeyAdapter() {
            private boolean isTyping = false;

            @Override
            public void keyTyped(KeyEvent e) {
                if (!isTyping) {
                    writer.println("/typing");
                    isTyping = true;
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (isTyping && textField.getText().isEmpty()) {
                    writer.println("/stoptyping");
                    isTyping = false;
                }
            }
        });
        inputPanel.add(textField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 5, 5));
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());
        buttonPanel.add(sendButton);

        JButton fileButton = new JButton("File");
        fileButton.addActionListener(e -> sendFile());
        buttonPanel.add(fileButton);

        JButton emojiButton = new JButton("😊");
        emojiButton.addActionListener(e -> showEmojiPicker());
        buttonPanel.add(emojiButton);

        inputPanel.add(buttonPanel, BorderLayout.EAST);

        typingLabel = new JLabel(" ");
        inputPanel.add(typingLabel, BorderLayout.SOUTH);

        add(inputPanel, BorderLayout.SOUTH);

        setUsernameAndConnect();
        applyTheme();
    }

    private void setUsernameAndConnect() {
        username = JOptionPane.showInputDialog(this, "Enter your username:", "Login", JOptionPane.PLAIN_MESSAGE);
        if (username == null || username.trim().isEmpty()) System.exit(0);
        username = username.trim();
        userColors.put(username, Color.BLUE);
        connectToServer();
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 12345);
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            new Thread(() -> {
                try {
                    String message;
                    while ((message = reader.readLine()) != null) {
                        if (message.equals("SUBMIT_USERNAME")) {
                            writer.println(username);
                        } else if (message.equals("SUBMIT_KEY")) {
                            writer.println(sessionKey);
                        } else if (message.equals("USERNAME_ACCEPTED")) {
                            setTitle("Chat - " + username);
                        } else if (message.startsWith("USERLIST:")) {
                            updateUserList(message.substring(9));
                        } else if (message.startsWith("MSG:")) {
                            appendColoredMessage(message.substring(4), false);
                        } else if (message.startsWith("PM:")) {
                            appendPrivateMessage(message.substring(3));
                        } else if (message.startsWith("FILE:")) {
                            appendFileMessage(message.substring(5));
                        } else if (message.startsWith("TYPING:")) {
                            updateTypingStatus(message.substring(7));
                        } else if (message.startsWith("READ:")) {
                            appendReadReceipt(message.substring(5));
                        } else if (message.startsWith("EDIT:")) {
                            updateEditedMessage(message.substring(5));
                        } else if (message.startsWith("DELETE:")) {
                            deleteMessage(message.substring(7));
                        } else if (message.startsWith("PIN:")) {
                            pinMessage(message.substring(4));
                        } else {
                            appendSystemMessage(message);
                        }
                    }
                } catch (IOException e) {
                    appendSystemMessage("Connection lost: " + e.getMessage());
                }
            }).start();

        } catch (IOException e) {
            appendSystemMessage("Error connecting to server: " + e.getMessage());
        }
    }

    private void sendMessage() {
        String message = textField.getText().trim();
        if (!message.isEmpty()) {
            writer.println(message);
            if (message.startsWith("/pm ")) {
                String[] parts = message.split(" ", 3);
                if (parts.length == 3) writer.println("/read " + parts[1]);
            }
            textField.setText("");
            writer.println("/stoptyping");
        }
    }

    private void sendFile() {
        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            writer.println("/file " + file.getName());
        }
    }

    private void showEmojiPicker() {
        String[] emojis = {"😊", "😂", "😍", "👍", "👎", "❤️"};
        String selected = (String) JOptionPane.showInputDialog(this, "Pick an emoji:", "Emoji Picker",
                JOptionPane.PLAIN_MESSAGE, null, emojis, emojis[0]);
        if (selected != null) textField.setText(textField.getText() + selected);
    }

    private void showMessageContextMenu(int x, int y) {
        try {
            StyledDocument doc = messageArea.getStyledDocument();
            int offset = messageArea.viewToModel2D(new Point(x, y));
            String text = doc.getText(0, doc.getLength());
            int lineStart = text.lastIndexOf("\n", offset) + 1;
            String line = text.substring(lineStart, text.indexOf("\n", lineStart) == -1 ? text.length() : text.indexOf("\n", lineStart));
            String[] parts = line.split(":", 4);
            if (parts.length >= 4 && parts[1].trim().equals(username)) {
                String msgId = messageIds.get(line.trim());
                if (msgId != null) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem edit = new JMenuItem("Edit");
                    edit.addActionListener(e -> {
                        String newText = JOptionPane.showInputDialog("Edit message:", parts[3].replace("[Edited]", ""));
                        if (newText != null) writer.println("/edit " + msgId + " " + newText);
                    });
                    menu.add(edit);

                    JMenuItem delete = new JMenuItem("Delete");
                    delete.addActionListener(e -> writer.println("/delete " + msgId));
                    menu.add(delete);

                    JMenuItem pin = new JMenuItem("Pin");
                    pin.addActionListener(e -> writer.println("/pin " + msgId));
                    menu.add(pin);

                    menu.show(messageArea, x, y);
                }
            }
        } catch (BadLocationException e) {
            System.err.println("Error updating edited message: " + e.getMessage());
        }
    }

    private void updateUserList(String userString) {
        userListModel.clear();
        String[] users = userString.split(",");
        for (String user : users) {
            if (!user.isEmpty()) {
                userListModel.addElement(user);
                if (!userColors.containsKey(user)) userColors.put(user, new Color((int)(Math.random() * 0x1000000)));
            }
        }
    }

    private void appendColoredMessage(String message, boolean isPrivate) {
        String[] parts = message.split(":", 5);
        if (parts.length < 5) return;
        String msgId = parts[1];
        String sender = parts[2];
        String timestamp = parts[3];
        String text = parts[4];
        
        StyledDocument doc = messageArea.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, userColors.getOrDefault(sender, Color.BLACK));
        if (isPrivate) StyleConstants.setItalic(attrs, true);
        
        String displayText = "[" + timestamp + "] " + sender + ": " + text + "\n";
        try {
            doc.insertString(doc.getLength(), displayText, attrs);
            messageIds.put(displayText.trim(), msgId);
        } catch (BadLocationException e) {
            System.err.println("Error appending colored message: " + e.getMessage());
        }
    }

    private void appendPrivateMessage(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length < 3) return;
        String sender = parts[0];
        String timestamp = parts[1];
        String encryptedText = parts[2];
        String text = decryptMessage(encryptedText);
        
        if (sender.equals(username) || textField.getText().startsWith("/pm " + sender)) {
            StyledDocument doc = messageArea.getStyledDocument();
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, userColors.getOrDefault(sender, Color.BLACK));
            StyleConstants.setItalic(attrs, true);
            
            try {
                doc.insertString(doc.getLength(), "[" + timestamp + "] PM from " + sender + ": " + text + "\n", attrs);
            } catch (BadLocationException e) {
                System.err.println("Error appending private message: " + e.getMessage());
            }
        }
    }

    private void appendSystemMessage(String message) {
        StyledDocument doc = messageArea.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, Color.GRAY);
        
        try {
            doc.insertString(doc.getLength(), message + "\n", attrs);
        } catch (BadLocationException e) {
            System.err.println("Error appending system message: " + e.getMessage());
        }
    }

    private void appendFileMessage(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length < 3) return;
        String sender = parts[0];
        String timestamp = parts[1];
        String fileName = parts[2];
        
        StyledDocument doc = messageArea.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, userColors.getOrDefault(sender, Color.BLACK));
        StyleConstants.setUnderline(attrs, true);
        
        try {
            doc.insertString(doc.getLength(), "[" + timestamp + "] " + sender + " sent file: " + fileName + "\n", attrs);
        } catch (BadLocationException e) {
            System.err.println("Error appending file message: " + e.getMessage());
        }
    }

    private void appendReadReceipt(String message) {
        String[] parts = message.split(":", 2);
        if (parts.length < 2) return;
        String sender = parts[0];
        String timestamp = parts[1];
        
        StyledDocument doc = messageArea.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, Color.GREEN);
        
        try {
            doc.insertString(doc.getLength(), "[" + timestamp + "] " + sender + " read your message\n", attrs);
        } catch (BadLocationException e) {
            System.err.println("Error appending read receipt: " + e.getMessage());
        }
    }

    private void updateEditedMessage(String message) {
        String[] parts = message.split(":", 5);
        if (parts.length < 5) return;
        String msgId = parts[1];
        String sender = parts[2];
        String timestamp = parts[3];
        String text = parts[4];
        
        StyledDocument doc = messageArea.getStyledDocument();
        try {
            String oldText = doc.getText(0, doc.getLength());
            for (String line : oldText.split("\n")) {
                if (line.contains(msgId)) {
                    int start = oldText.indexOf(line);
                    doc.remove(start, line.length());
                    SimpleAttributeSet attrs = new SimpleAttributeSet();
                    StyleConstants.setForeground(attrs, userColors.getOrDefault(sender, Color.BLACK));
                    doc.insertString(start, "[" + timestamp + "] " + sender + ": " + text + "\n", attrs);
                    break;
                }
            }
        } catch (BadLocationException e) {
            System.err.println("Error updating edited message: " + e.getMessage());
        }
    }

    private void deleteMessage(String message) {
        String[] parts = message.split(":", 2);
        if (parts.length < 2) return;
        String msgId = parts[0];
        
        StyledDocument doc = messageArea.getStyledDocument();
        try {
            String text = doc.getText(0, doc.getLength());
            for (String line : text.split("\n")) {
                if (line.contains(msgId)) {
                    int start = text.indexOf(line);
                    doc.remove(start, line.length() + 1); // +1 for newline
                    break;
                }
            }
        } catch (BadLocationException e) {
            System.err.println("Error deleting message: " + e.getMessage());
        }
    }

    private void pinMessage(String message) {
        String[] parts = message.split(":", 4);
        if (parts.length < 4) return;
        String sender = parts[1];
        String timestamp = parts[2];
        String text = parts[3];
        
        StyledDocument doc = pinnedArea.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, userColors.getOrDefault(sender, Color.BLACK));
        
        try {
            doc.insertString(doc.getLength(), "[" + timestamp + "] " + sender + ": " + text + "\n", attrs);
        } catch (BadLocationException e) {
            System.err.println("Error pinning message: " + e.getMessage());
        }
    }

    private void updateTypingStatus(String typingUsers) {
        typingLabel.setText(typingUsers.isEmpty() ? " " : typingUsers + " is typing...");
    }

    private String decryptMessage(String encryptedText) {
        try {
            SecretKeySpec key = new SecretKeySpec(sessionKey.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted);
        } catch (javax.crypto.NoSuchPaddingException | javax.crypto.BadPaddingException | 
                 javax.crypto.IllegalBlockSizeException | java.security.NoSuchAlgorithmException | 
                 java.security.InvalidKeyException | IllegalArgumentException e) {
            System.err.println("Error decrypting message: " + e.getMessage());
            return encryptedText;
        }
    }

    private void applyTheme() {
        String theme = (String) themeCombo.getSelectedItem();
        switch (theme) {
            case "Light" -> {
                getContentPane().setBackground(Color.WHITE);
                messageArea.setBackground(Color.WHITE);
                pinnedArea.setBackground(Color.LIGHT_GRAY);
            }
            case "Dark" -> {
                getContentPane().setBackground(Color.DARK_GRAY);
                messageArea.setBackground(Color.DARK_GRAY);
                pinnedArea.setBackground(Color.GRAY);
                messageArea.setForeground(Color.WHITE);
                pinnedArea.setForeground(Color.WHITE);
            }
            case "Blue" -> {
                getContentPane().setBackground(new Color(173, 216, 230));
                messageArea.setBackground(new Color(173, 216, 230));
                pinnedArea.setBackground(new Color(135, 206, 235));
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatClient client = new ChatClient();
            client.setVisible(true);
        });
    }
}