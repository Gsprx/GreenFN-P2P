import misc.Config;
import misc.Function;
import misc.TypeChecking;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
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
    boolean isPeerOnline;
    ServerSocket server;

    public Peer(String ip, int port, String shared_directory) {
        this.ip = ip;
        this.port = port;
        this.shared_directory = shared_directory;
        this.createFileDownloadList();
        filesInNetwork = this.peersFilesInNetwork();
        isPeerOnline = false;
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
                option = inp.nextLine().toLowerCase().trim();
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
        username = inp.nextLine().trim();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine().trim();

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
        username = inp.nextLine().trim();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine().trim();

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
            isPeerOnline = true;
            this.tokenID = response;
            // start the thread for the server
            Thread runServer = new Thread(()-> {
                try {
                    server = new ServerSocket(this.port);
                    while(isPeerOnline) {
                        Socket inConnection = server.accept();
                        Thread t = new PeerServerThread(inConnection, filesInNetwork, this.shared_directory);
                        t.start();
                    }
                } catch (IOException e) {
                    // If the thread is currently listening for requests it will listen to a closed server socket
                    // meaning we will get a socket closed exception
                    // In that case we ignore the exception and the thread closes
                    if (!e.getMessage().equals("Socket closed")) throw new RuntimeException(e);
                }
            });
            runServer.start();

            sendTrackerInformation(this.tokenID);
            runLoggedIn(this.tokenID);
        }
        else System.out.println("[-] Wrong credentials\n");
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
                option = inp.nextLine().toLowerCase().trim();
                if (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5"));

            // manage options
            switch (option) {
                // option 1: list
                case "1":
                    this.list();
                    break;
                // option 2: details
                case "2":
                    this.details(null);
                    break;
                // option 3: check active
                case "3":
                    checkActive(null, 0);
                    break;
                // option 4: simple download
                case "4":
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
            System.out.println();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Option 2 | Details
     * Send Tracker the name of an available file.
     * Request from Tracker the details (ip, port, count_downloads, count_failures) of network peers for the specific file,
     * OR receive FAIL notification if the files does not exist anymore in the network.
     * @param filename The name of the file we want to look up.
     * @return A pair of the peers that own the files along with the statistics of each peer for the file.
     */
    private Map.Entry<ArrayList<String[]>,ArrayList<int[]>> details(String filename)    {
        System.out.println("\n|Details|");
        //Input from peer - filename
        if (filename == null) {
            System.out.print("Enter file name you want to look up (exit if don't want to loop up for anything): ");
            Scanner inp = new Scanner(System.in);
            filename = inp.nextLine().trim();
            if(filename.equals("exit")) {
                System.out.println("Exiting...\n");
                return null;
            }
        }
        //Send Tracker request to receive file's information
        try {
            Socket tracker = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(tracker.getOutputStream());
            //send function code to Tracker
            out.writeInt(Function.REPLY_DETAILS.getEncoded());
            out.flush();
            //send tokenID
            out.writeInt(this.tokenID);
            out.flush();
            out.writeObject(filename);
            out.flush();

            ObjectInputStream in = new ObjectInputStream(tracker.getInputStream());
            int verificationCode = in.readInt();
            switch (verificationCode){
                case -1:
                    System.out.println("No active owners found of requested file\n");
                    break;
                case 0:
                    System.out.println("File does not exist within the network.\n");
                    break;
                case 1:
                    System.out.println("File's details:");
                    ArrayList<String[]> fileOwnersInfo = (ArrayList<String[]>) in.readObject();
                    ArrayList<int[]> fileOwnersStatistics = (ArrayList<int[]>) in.readObject();
                    System.out.println("Peer\t|" + "IP\t\t\t\t|" + "Port\t|" + "Username\t\t\t\t|" + "Downloads\t\t|" + "Fails");
                    for(int i=0; i<fileOwnersInfo.size(); i++){
                        //Maybe write somewhere what are the values we see. Preferable before this for
                        System.out.println("--------|---------------|-------|-----------------------|---------------|-------------------");
                        System.out.print((i + 1) + "\t".repeat(2 - (Integer.toString((i + 1)).length() / 4)) + "|");
                        for(int j=0; j<fileOwnersInfo.get(i).length; j++){
                            if (j==0) // ip
                                System.out.print(fileOwnersInfo.get(i)[j] + "\t".repeat(4 - (fileOwnersInfo.get(i)[j].length() / 4)) + "|");
                            else if (j==1) // port
                                System.out.print(fileOwnersInfo.get(i)[j] + "\t".repeat(2 - (fileOwnersInfo.get(i)[j].length() / 4)) + "|");
                            else // username
                                System.out.print(fileOwnersInfo.get(i)[j] + "\t".repeat(6 - (fileOwnersInfo.get(i)[j].length() / 4)) + "|");
                        }
                        for (int j=0; j<fileOwnersStatistics.get(i).length; j++){
                            if (j == 0) // downloads
                                System.out.print(fileOwnersStatistics.get(i)[j] + "\t".repeat(4 - (Integer.toString(fileOwnersStatistics.get(i)[j]).length() / 4)) + "|");
                            else // fails
                                System.out.print(fileOwnersStatistics.get(i)[j]);
                        }
                        System.out.println();
                    }
                    System.out.println();
                    return new AbstractMap.SimpleEntry<>(fileOwnersInfo,fileOwnersStatistics);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Option 3 | Check Active
     * Try pinging a certain peer.
     * If he responds, then he is active.
     * @param _ip The given ip address.
     * @param _port The given port number.
     * @return If the user with _ip and _port is active.
     */
    private boolean checkActive(String _ip, int _port) {
        System.out.println("\n|Check Active|");

        String ip = _ip;
        int port = _port;

        // if ip is null ask for ip and port
        if (ip == null) {
            // read ip
            boolean is_ipv4 = false;
            while (!is_ipv4) {
                // get a string of the ip address
                System.out.print("Enter peer ip address: ");
                Scanner inp = new Scanner(System.in);
                ip = inp.nextLine().trim();
                // check if it is an ip address
                is_ipv4 = TypeChecking.isIPv4(ip) || ip.equals("localhost");
            }

            // read port
            boolean is_int = false;
            while (!is_int) {
                // get a string of the port
                System.out.print("Enter peer port: ");
                Scanner inp = new Scanner(System.in);
                String ans = inp.nextLine().trim();
                // check if answer is int
                is_int = TypeChecking.isInteger(ans);
                if (is_int) port = Integer.parseInt(ans);
            }
        }

        try {
            Socket socket = new Socket(ip, port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());

            // write to peer
            out.writeInt(Function.CHECK_ACTIVE.getEncoded());
            out.flush();
            // wait for response
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            int response = (int) in.readObject();
            System.out.println("[+] Host is active.\n");

            out.close();
            in.close();
            socket.close();
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("[-] Host with ip: [" + ip + "] at port: [" + port + "] is not found.\n");
            return false;
        }
        return true;
    }
    /**
     * Option 4 | Simple download
     * */
    private void downloadFile(){
        // get file name
        System.out.println("\n|Download File|");
        System.out.print("File name: ");
        Scanner inp = new Scanner(System.in);
        String fileName = inp.nextLine().trim();
        if(fileName.equals("exit")){
            System.out.println("Exiting...\n");
            return;
        }
        try {
            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());
            if(filesInSharedDirectory.contains(fileName)){
                System.out.println("You already have this file in your shared directory <3\n");
                return;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // get the details of the file the peer wants to download
        Map.Entry<ArrayList<String[]>, ArrayList<int[]>> detailsResult = details(fileName);
        if (detailsResult == null) return;
        ArrayList<String[]> fileOwnersInfo = detailsResult.getKey();
        ArrayList<int[]> fileOwnersStatistics = detailsResult.getValue();

        // check the peers who own this file and are active
        ArrayList<String[]> activeFileOwners = new ArrayList<>();
        ArrayList<int[]> activeOwnersStats = new ArrayList<>();
        // hash map with key: peer, value: formula result of peer's statistics
        HashMap<String[], Double> queue = new HashMap<>();
        for (int i=0; i<fileOwnersInfo.size(); i++) {
            // if peer is active add him and his statistics to the candidate list
            System.out.println("Checking if host " + fileOwnersInfo.get(i)[2] + " is active...");
            if (checkActive(fileOwnersInfo.get(i)[0], Integer.parseInt(fileOwnersInfo.get(i)[1]))) {
                // add the peer to a candidate list
                activeFileOwners.add(fileOwnersInfo.get(i));
                // add his statistics to a candidate list
                activeOwnersStats.add(fileOwnersStatistics.get(i));
                // add the peer with his priority modifier to the hashMap
                queue.put(fileOwnersInfo.get(i), priorityFormula(fileOwnersStatistics.get(i)[0], fileOwnersStatistics.get(i)[1]));
            }
        }

        // check if there are no active owners of the file
        if (activeFileOwners.isEmpty()) {
            System.out.println("There are no active owners for this file right now...\n");
            return;
        }

        // sort the array of peers
        ArrayList<Double> temp = new ArrayList<>();
        for (Map.Entry<String[], Double> entry : queue.entrySet()) {
            temp.add(entry.getValue());
        }
        Collections.sort(temp);
        ArrayList<String[]> sortedPeers = new ArrayList<>();
        for (double num : temp) {
            for (Map.Entry<String[], Double> entry : queue.entrySet()) {
                if (entry.getValue().equals(num)) {
                    sortedPeers.add(entry.getKey());
                }
            }
        }

        // try to download from peer
        boolean downloaded = false;
        while (!downloaded) {
            // check if the download failed from all the peers
            if (sortedPeers.isEmpty()) {
                System.out.println("Download failed from all the peers");
                return;
            }

            // get the first from the candidate list
            String[] currentPeer = sortedPeers.remove(0);

            // try to establish connection with peer
            try {
                Socket downloadSocket = new Socket(currentPeer[0], Integer.parseInt(currentPeer[1]));
                ObjectOutputStream out = new ObjectOutputStream(downloadSocket.getOutputStream());

                // code for simple download: 8
                out.writeInt(Function.SIMPLE_DOWNLOAD.getEncoded());
                out.writeObject(fileName);
                out.flush();

                // wait for response
                ObjectInputStream in = new ObjectInputStream(downloadSocket.getInputStream());
                int result = (int) in.readObject();
                // result 0 = file does not exist
                if (result == 0) {
                    System.out.println("This file does not exist...");
                }
                // result 1 = file exists
                else {
                    String pathToStore = this.shared_directory + File.separator + fileName;
                    FileOutputStream fileOutputStream = new FileOutputStream(pathToStore);
                    // Delete existing data from the file
                    fileOutputStream.getChannel().truncate(0);
                    fileOutputStream.close(); // Close the file stream to ensure truncation takes effect
                    //Open to write in the file
                    fileOutputStream = new FileOutputStream(pathToStore, true);
                    BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
                    //read file parts to be received
                    int fileParts = in.readInt();
                    byte[] fileBytes;
                    for (int i=0; i<fileParts; i++){
                        fileBytes = new byte[Config.DOWNLOAD_SIZE];
                        int bytesRead;
                        while ((bytesRead = in.read(fileBytes)) != -1) {
                            //System.out.println("fileBytes.getClass() "+fileBytes.getClass());
                            bufferedOutputStream.write(fileBytes, 0, bytesRead);
                        }
                        bufferedOutputStream.flush();
                    }
                    bufferedOutputStream.close();
                    // close
                    out.close();
                    in.close();
                    downloadSocket.close();

                    // notify tracker for successful download
                    System.out.println("File received successfully.");
                    notifyTracker(1, currentPeer, fileName);
                    downloaded = true;
                }
            } catch (IOException e) {
                System.out.println("Something went wrong...Could not download file from peer.");
                e.printStackTrace();
                // inform tracker for failed download
                notifyTracker(0, currentPeer, fileName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private double priorityFormula(int countDownloads, int countFailures) {
        return Math.pow(0.75, countDownloads) * Math.pow(1.25, countFailures);
    }

    private void notifyTracker(int code, String[] currentPeer, String fileName) {
        try {
            Socket notifyTrackerForFail = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream tracker_out = new ObjectOutputStream(notifyTrackerForFail.getOutputStream());
            // code for notify: 5
            tracker_out.writeInt(Function.PEER_NOTIFY.getEncoded());
            if (code == 0) {
                // code for failed download: 0
                tracker_out.writeInt(code);
                // token-id
                tracker_out.writeInt(this.tokenID);
            } else {
                // code for successful download: 1
                tracker_out.writeInt(code);
                // token-id
                tracker_out.writeInt(this.tokenID);
                // file name
                tracker_out.writeObject(fileName);
            }
            // peer username
            tracker_out.writeObject(currentPeer[2]);
            tracker_out.flush();
            // close output stream
            tracker_out.close();
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
        int response;
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            //Send register code
            out.writeInt(Function.LOGOUT.getEncoded());
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
            // close the server socket
            isPeerOnline = false;
            try {
                server.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Message = "[+] You managed to logout successfully.\n";
        }else {
            Message = "[-] An error occurred during logout.\n";
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
    private void createFileDownloadList() {
        try {
            // create or check if the file already exists in the direcotry
            File fileDownloadList = new File(this.shared_directory + File.separator + "fileDownloadList.txt");
            if (fileDownloadList.createNewFile()) {
                System.out.println("File created: " + fileDownloadList);
            } else {
                System.out.println("fileDownloadList.txt found correctly at " + fileDownloadList);
            }

            // write the files
            String[] fileDownloadListContent = Config.fileDownloadList;
            FileWriter writer = new FileWriter(fileDownloadList);
            for (String files : fileDownloadListContent) {
                writer.write(files);
                writer.write(System.lineSeparator());
            }
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check the files that are in the fileDownloadList.txt and in the shared directory.
     * @return matchingFiles - the common files from the txt and the directory.
     */
    private ArrayList<String> peersFilesInNetwork() {
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
        Peer peer = new Peer(args[0], Integer.parseInt(args[1]), args[2]);
        // start the thread for the user
        Thread runPeer = new Thread(peer::runPeer);
        runPeer.start();
    }
}
