import misc.Config;
import misc.Function;
import misc.TypeChecking;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;

public class Peer {
    private String ip;
    private int port;
    private String shared_directory;
    private int tokenID;
    private ArrayList<String> filesInNetwork;

    public Peer(String ip, int port, String shared_directory) {
        this.ip = ip;
        this.port = port;
        this.shared_directory = shared_directory;
        this.createFileDownloadList();
        filesInNetwork = this.peersFilesInNetwork();
    }

    /**
     * Run peer.
     * This method is called when we want to run a peer to show and manage options.
     */
    public void runPeer() {
        boolean running = true;
        // peer actions
        while (running) {
            System.out.println("1) Register\n2) Login\n");
            String option;
            // if the user gives invalid info then ask again
            do {
                System.out.print("> ");
                Scanner inp = new Scanner(System.in);
                option = inp.nextLine().toLowerCase();
                if (option.equals("exit")) {
                    running = false;
                }
                else if (!option.equals("1") && !option.equals("2")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("exit"));

            // if running = false, that means user requested exit
            if (!running) continue;

            // manage options
            // option 1: register peer
            if (option.equals("1")) {
                peerRegister();
                System.out.println("-------------------------------------------\n");
            }
            // option 2: peer login
            else {
                peerLogin();
            }
        }
    }

    /**
     * Register peer.
     * This method is called if the user chose to register.
     * Creates a new account and sends it to the tracker.
     */
    private void peerRegister() {
        System.out.println("\n|Register User|");

        // account attributes
        String username;
        String password;

        // username
        System.out.print("Enter username: ");
        Scanner inp = new Scanner(System.in);
        username = inp.nextLine();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine();

        int response;
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            //Send register code
            out.writeInt(Function.REGISTER.getEncoded());
            //Send username
            out.writeObject(username);
            //Send encrypted password
            String encryptedPassword;
            try {
                encryptedPassword = this.hashString(password);
            }catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            out.writeObject(encryptedPassword);
            out.flush();
            //Read 1(success), or 0(fail)
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            response = in.readInt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (response == 1) System.out.println("[+] User registered successfully");
        else System.out.println("[-] User already exists");
    }

    /**
     * Peer Login.
     * This method is called if the user chose to log in.
     * Enter details of account and send it to the tracker for validation and verification.
     * After successful logIn tracker responds with token_id.
     * After logging in present more functionalities to the peer.
     */
    private void peerLogin() {
        System.out.println("\n|User Login|");

        // account attributes
        String username;
        String password;

        // username
        System.out.print("Enter username: ");
        Scanner inp = new Scanner(System.in);
        username = inp.nextLine();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine();

        int response;
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            //Send register code
            out.writeInt(Function.LOGIN.getEncoded());
            out.flush();
            //Send username
            out.writeObject(username);
            out.flush();
            //Send encrypted password
            String encryptedPassword;
            try {
                encryptedPassword = this.hashString(password);
            }catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            out.writeObject(encryptedPassword);
            out.flush();
            //Read tokenID(success), or 0(fail)
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            response = in.readInt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (response != 0) {
            this.tokenID = response;
            // start the thread for the server
            Thread runServer = new Thread(()-> {
                try {
                    ServerSocket server = new ServerSocket(this.port);
                    while(true){
                        Socket inConnection = server.accept();
                        Thread t = new PeerServerThread(inConnection, filesInNetwork);
                        t.start();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            runServer.start();

            sendTrackerInformation(this.tokenID);
            runLoggedIn(this.tokenID);
        }
        else System.out.println("[-] Wrong credentials");
    }

    /**
     * After the peer logged in, he has more options.
     */
    private void runLoggedIn(int token) {
        System.out.println("\n------------------Welcome------------------");
        boolean running = true;
        // logged in peer actions
        while (running) {
            System.out.println("1) List\n2) Details\n3) Check Active\n4) Simple Download\n5) LogOut\n");
            String option;
            // if the user gives invalid info then ask again
            do {
                System.out.print("> ");
                Scanner inp = new Scanner(System.in);
                option = inp.nextLine().toLowerCase();
                if (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5"));

            // manage options
            switch (option) {
                // option 1: list
                case "1":
                    // TODO: List
                    this.list();
                    break;
                // option 2: details
                case "2":
                    // TODO: Details
                    this.details();
                    break;
                // option 3: check active
                case "3":
                    checkActive();
                    break;
                // option 4: simple download
                case "4":
                    // TODO: Simple Download
                    this.downloadFile();
                    break;
                // option 5: logout
                default:
                    logout(this.tokenID);
                    running = false;
                    break;
            }
        }
    }

    /**
     * Option 1 | List
     * Request from Tracker the list of available files within the P2P network.
     */
    private void list() {
        System.out.println("\n|List|");
        try {
            Socket tracker = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(tracker.getOutputStream());
            //send function code to Tracker
            out.writeInt(Function.REPLY_LIST.getEncoded());
            out.flush();
            //send tokenID
            out.writeInt(this.tokenID);
            out.flush();
            //read files
            ObjectInputStream in = new ObjectInputStream(tracker.getInputStream());
            ArrayList<String> files = (ArrayList<String>) in.readObject();
            if(!files.isEmpty()){
                System.out.println("The available files are: ");
                files.forEach(System.out::println);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Option 2 | Details
     * Send Tracker the name of an available file.
     * Request from Tracker the details (ip, port, count_downloads, count_failures) of network peers for the specific file,
     * OR receive FAIL notification if the files does not exist anymore in the network.
     */
    private Map.Entry<ArrayList<String[]>,ArrayList<int[]>> details() {
        System.out.println("\n|Details|");
        //Input from peer - filename
        String filename;
        System.out.println("Enter file name you want to receive. (exit if don't want to download anything)");
        Scanner inp = new Scanner(System.in);
        filename = inp.nextLine();
        System.out.println();
        if(filename.equals("exit")){
            System.out.println("Exiting details method.");
            //return null;
            return new AbstractMap.SimpleEntry<>(new ArrayList<String[]>(),new ArrayList<int[]>());
        }
        //Send Tracker request to receive file's information
        try {
            Socket tracker = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(tracker.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(tracker.getInputStream());
            //send function code to Tracker
            out.writeInt(Function.REPLY_DETAILS.getEncoded());
            out.flush();
            //send tokenID
            out.writeInt(this.tokenID);
            out.flush();
            out.writeObject(filename);
            out.flush();
            int verificationCode = in.readInt();
            switch (verificationCode){
                case -1:
                    System.out.println("No active owners found of requested file");
                    break;
                case 0:
                    System.out.println("File does not exist within the network.");
                    break;
                case 1:
                    System.out.println("File's details:");
                    ArrayList<String[]> fileOwnersInfo = (ArrayList<String[]>) in.readObject();
                    ArrayList<int[]> fileOwnersStatistics = (ArrayList<int[]>) in.readObject();
                    for(int i=0; i<fileOwnersInfo.size(); i++){
                        //Maybe write somewhere what are the values we see. Preferable before this for
                        System.out.println("Peer "+i+": ");
                        for(int j=0; j<fileOwnersInfo.get(i).length;j++){
                            System.out.print(fileOwnersInfo.get(i)[j]+ " ");
                        }
                        for (int j=0; j<fileOwnersStatistics.get(i).length; j++){
                            System.out.print(fileOwnersStatistics.get(i)[j]+ " ");
                        }
                        System.out.println();
                        return new AbstractMap.SimpleEntry<>(fileOwnersInfo,fileOwnersStatistics);
                        //alt+shift_insert poly grenn fn combo
                    }
                    break;
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return new AbstractMap.SimpleEntry<>(new ArrayList<String[]>(),new ArrayList<int[]>());
    }

    /**
     * Option 3 | Check Active
     * Try pinging a certain peer.
     * If he responds, then he is active.
     */
    private void checkActive() {
        System.out.println("\n|Check Active|");

        String ip = null;
        int port = 6000;

        // read ip
        boolean is_ipv4 = false;
        while (!is_ipv4) {
            // get a string of the ip address
            System.out.print("Enter peer ip address: ");
            Scanner inp = new Scanner(System.in);
            ip = inp.nextLine();
            // check if it is an ip address
            is_ipv4 = TypeChecking.isIPv4(ip) || ip.equals("localhost");
        }

        // read port
        boolean is_int = false;
        while (!is_int) {
            // get a string of the port
            System.out.print("Enter peer port: ");
            Scanner inp = new Scanner(System.in);
            String ans = inp.nextLine();
            // check if answer is int
            is_int = TypeChecking.isInteger(ans);
            if (is_int) port = Integer.parseInt(ans);
        }

        try {
            Socket socket = new Socket(ip, port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());

            // write to peer
            out.writeInt(Function.CHECK_ACTIVE.getEncoded());
            out.flush();
            // wait for response
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            String response = (String) in.readObject();
            System.out.println(response + "\n");

            out.close();
            in.close();
            socket.close();
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("[-] Host with ip: [" + ip + "] at port: [" + port + "] is not found.\n");
        }
    }
    /**
     * Option 4 | Simple download
     * */
    private void downloadFile(){
        /* NOTES for AvraBeast
        This is how you do it more or less. This part is for Receiving.
        */
        try {
            //Connect with the peer.
            Socket connectionToPeer = new Socket();
            ObjectInputStream inputStream = new ObjectInputStream(connectionToPeer.getInputStream());;
            FileOutputStream fileOutputStream = new FileOutputStream("ReceivedFile.txt");
            // Delete existing data from the file
            fileOutputStream.getChannel().truncate(0);
            fileOutputStream.close(); // Close the file stream to ensure truncation takes effect
            //Open to write in the file
            fileOutputStream = new FileOutputStream("ReceivedFile.txt", true);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            //read file parts to be received
            Double fileParts= inputStream.readDouble();
            byte[] fileBytes;
            for (int i=0; i<fileParts; i++){
                fileBytes = new byte[30]; //Have a place to store the number of bytes. Something like "Function.SIMPLE_DOWNLOAD.getMaxBytesPerPart()"
                int bytesRead;
                while ((bytesRead = inputStream.read(fileBytes)) != -1) {
//                    System.out.println("fileBytes.getClass() "+fileBytes.getClass());
                    bufferedOutputStream.write(fileBytes, 0, bytesRead);
                }
                bufferedOutputStream.flush();
            }
            connectionToPeer.close();
            System.out.println("File received successfully.");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Option 5 | User LogOut
     * This method is called if the user chose to log out.
     * Send request to tracker so the tracker can remove token_id of user
     */
    private void logout(int token) {
        // TODO: Send logout request to tracker
        int response;
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            //Send register code
            out.writeInt(Function.LOGOUT.getEncoded());
            out.flush();
            //Send tokenID
            out.writeInt(token);
            out.flush();
            // wait for input
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            response = in.readInt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // wait for response (temp response string below)
        String Message;
        if(response==1){
            Message = "[+] Your smelly ass has managed to logout, don't show up here never again or you'll be smoked on ma mama.";
        }else {
            Message = "[-] You donkey kong can't even log out properly.";
        }
        System.out.println(Message);
    }

    /**
     * Send Tracker Information
     * This method is called after user log in to update tracker about the peer's information.
     * Send tokenID, files(Name because its unique), peerIP, peerPort to tracker so the tracker can store them.
     */
    private void sendTrackerInformation(int token) {
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            //Send register code
            out.writeInt(Function.PEER_INFORM.getEncoded());
            out.flush();
            //Send tokenID
            out.writeInt(token);
            out.flush();
            //Send files
            out.writeObject(filesInNetwork);
            out.flush();
            //Send peerIP
            out.writeObject(this.ip);
            out.flush();
            //Send peerPort
            out.writeObject(Integer.toString(this.port));
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hashing any String.
     * Use the password as input, so you can send the hashed string to tracker.
     * Enhances security.
     */
    private String hashString(String input) throws NoSuchAlgorithmException{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());

            // Convert byte array to a hexadecimal string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
    }
    /**
     * Create a txt file, where we store the verified files that can be shared within the p2p network.
     */
    private void createFileDownloadList(){
        try {
            String fileDownloadListDirectory = this.shared_directory + File.separator + "fileDownloadList.txt";
            String[] fileDownloadListContent = {"file1.txt","file2.txt","file3.txt","file4.txt","file5.txt",};
            FileWriter writer = new FileWriter(fileDownloadListDirectory);
            for (String files : fileDownloadListContent) {
                writer.write(files);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Check the files that are in the fileDownloadList.txt and in the shared directory.
     * @return matchingFiles - the common files from the txt and the directory.
     */
    private ArrayList<String> peersFilesInNetwork(){
        try {
            //take the files from fileDownloadList
            List<String> filesInFileDownloadList = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader(this.shared_directory + File.separator + "fileDownloadList.txt"));
            String downloadableFile;
            while ((downloadableFile = reader.readLine()) != null) {
                // Add each line to the list
                filesInFileDownloadList.add(new String(downloadableFile));
            }

            //take the files that are in your directory
            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());

            /*
            For debugging purposes
            System.out.println("Files: "+filesInSharedDirectory);
            System.out.println("Class: "+filesInSharedDirectory.getClass());
            filesInSharedDirectory.stream().forEach(System.out::println);
            */

            //save the common files - return
            ArrayList<String> matchingFiles = new ArrayList<>();
            for(String file : filesInFileDownloadList){
                if(filesInSharedDirectory.contains(file)){
                    matchingFiles.add(new String(file));
                }
            }

            return matchingFiles;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        //IP-Port-Shared_Directory Path
        Peer peer = new Peer(args[0], Integer.parseInt(args[1]), String.valueOf(args[2]));
        // start the thread for the user
        Thread runPeer = new Thread(peer::runPeer);
        runPeer.start();
    }
}
