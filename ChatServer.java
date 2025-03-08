import java.io.*;
import java.net.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

public class ChatServer {
    private static final int PORT = 12345;
    private static final Map<String, PrintWriter> clients = new HashMap<>();
    private static final Map<String, String> clientKeys = new HashMap<>(); // Store client public keys
    private static final List<String> messageHistory = new ArrayList<>();
    private static final Map<String, String> pinnedMessages = new HashMap<>(); // Message ID -> Message

    public static void main(String[] args) {
        System.out.println("Chat Server is running...");
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                new ClientHandler(serverSocket.accept()).start();
            }
        } catch (IOException e) {
            System.out.println("Error in the server: " + e.getMessage());
        }
    }

    public static Map<String, String> getPinnedMessages() {
        return pinnedMessages;
    }

    private static class ClientHandler extends Thread {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username;
        private final Set<String> typingUsers = new HashSet<>();
        private String sessionKey;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                
                while (true) {
                    out.println("SUBMIT_USERNAME");
                    username = in.readLine();
                    if (username == null) return;
                    synchronized (clients) {
                        if (!clients.containsKey(username)) {
                            clients.put(username, out);
                            break;
                        }
                    }
                }

                out.println("SUBMIT_KEY");
                sessionKey = in.readLine(); // Simplified key exchange
                clientKeys.put(username, sessionKey);

                out.println("USERNAME_ACCEPTED");
                synchronized (messageHistory) {
                    for (String msg : messageHistory) {
                        out.println(msg);
                    }
                }
                broadcast("USERLIST:" + String.join(",", clients.keySet()));
                broadcast(username + " has joined the chat");

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.startsWith("/pm ")) {
                        String[] parts = message.split(" ", 3);
                        if (parts.length == 3) sendPrivateMessage(username, parts[1], parts[2]);
                    } else if (message.startsWith("/file ")) {
                        String[] parts = message.split(" ", 2);
                        if (parts.length == 2) handleFileTransfer(username, parts[1]);
                    } else if (message.equals("/typing")) {
                        typingUsers.add(username);
                        broadcastTypingStatus();
                    } else if (message.equals("/stoptyping")) {
                        typingUsers.remove(username);
                        broadcastTypingStatus();
                    } else if (message.startsWith("/read ")) {
                        String[] parts = message.split(" ", 2);
                        if (parts.length == 2) sendReadReceipt(username, parts[1]);
                    } else if (message.startsWith("/edit ")) {
                        String[] parts = message.split(" ", 3);
                        if (parts.length == 3) editMessage(username, parts[1], parts[2]);
                    } else if (message.startsWith("/delete ")) {
                        String[] parts = message.split(" ", 2);
                        if (parts.length == 2) deleteMessage(username, parts[1]);
                    } else if (message.startsWith("/pin ")) {
                        String[] parts = message.split(" ", 2);
                        if (parts.length == 2) pinMessage(username, parts[1]);
                    } else {
                        String msgId = UUID.randomUUID().toString();
                        String formattedMessage = "MSG:" + msgId + ":" + username + ":" + getTimestamp() + ":" + message;
                        synchronized (messageHistory) {
                            messageHistory.add(formattedMessage);
                            if (messageHistory.size() > 100) messageHistory.remove(0);
                        }
                        broadcast(formattedMessage);
                    }
                }
            } catch (IOException e) {
                System.out.println("Error handling client: " + e.getMessage());
            } finally {
                if (username != null) {
                    synchronized (clients) {
                        clients.remove(username);
                        clientKeys.remove(username);
                        broadcast(username + " has left the chat");
                        broadcast("USERLIST:" + String.join(",", clients.keySet()));
                    }
                }
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Error closing socket: " + e.getMessage());
                }
            }
        }

        private void broadcast(String message) {
            synchronized (clients) {
                for (PrintWriter writer : clients.values()) {
                    writer.println(message);
                }
            }
        }

        private void sendPrivateMessage(String sender, String receiver, String message) {
            PrintWriter receiverWriter = clients.get(receiver);
            if (receiverWriter != null) {
                String encryptedMessage = encryptMessage(message, clientKeys.get(receiver));
                String pm = "PM:" + sender + ":" + getTimestamp() + ":" + encryptedMessage;
                receiverWriter.println(pm);
                clients.get(sender).println(pm);
            } else {
                out.println("User " + receiver + " not found");
            }
        }

        private void sendReadReceipt(String sender, String receiver) {
            PrintWriter receiverWriter = clients.get(receiver);
            if (receiverWriter != null) {
                receiverWriter.println("READ:" + sender + ":" + getTimestamp());
            }
        }

        private void handleFileTransfer(String sender, String filePath) {
            broadcast("FILE:" + sender + ":" + getTimestamp() + ":" + filePath);
        }

        private void editMessage(String sender, String msgId, String newText) {
            synchronized (messageHistory) {
                for (int i = 0; i < messageHistory.size(); i++) {
                    String[] parts = messageHistory.get(i).split(":", 5);
                    if (parts.length >= 5 && parts[1].equals(msgId) && parts[2].equals(sender)) {
                        messageHistory.set(i, "MSG:" + msgId + ":" + sender + ":" + getTimestamp() + "[Edited]:" + newText);
                        broadcast("EDIT:" + msgId + ":" + sender + ":" + getTimestamp() + "[Edited]:" + newText);
                        break;
                    }
                }
            }
        }

        private void deleteMessage(String sender, String msgId) {
            synchronized (messageHistory) {
                messageHistory.removeIf(msg -> msg.contains(msgId + ":" + sender));
                broadcast("DELETE:" + msgId + ":" + sender);
            }
        }

        private void pinMessage(String sender, String msgId) {
            synchronized (messageHistory) {
                for (String msg : messageHistory) {
                    if (msg.contains(msgId + ":" + sender)) {
                        pinnedMessages.put(msgId, msg);
                        broadcast("PIN:" + msgId + ":" + sender + ":" + msg.split(":", 5)[4]);
                        break;
                    }
                }
            }
        }

        private void broadcastTypingStatus() {
            String typingList = "TYPING:" + String.join(",", typingUsers);
            broadcast(typingList);
        }

        private String encryptMessage(String message, String key) {
            try {
                SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(), "AES");
                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                byte[] encrypted = cipher.doFinal(message.getBytes());
                return Base64.getEncoder().encodeToString(encrypted);
            } catch (InvalidKeyException | NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e) {
                System.out.println("Encryption error: " + e.getMessage());
                return message;
            }
        }

        private String getTimestamp() {
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        }
    }
}