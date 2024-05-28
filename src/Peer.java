import misc.Config;
import misc.Function;
import misc.TypeChecking;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class Peer {
    private String ip;
    private int port;
    private String shared_directory;
    private int tokenID;
    // the files in the peer's network that he has either completely or partially (partitions of them)
    private ArrayList<String> filesInNetwork;
    // the partitions for each of the files. Every hashset in the arraylist corresponds to the file at the same index
    // in filesInNetwork
    private ArrayList<HashSet<String>> partitionsInNetwork;
    // the files fow which this peer is a seeder
    private ArrayList<String> seederOfFiles;
    boolean isPeerOnline;
    ServerSocket server;
    private boolean isSeeder;

    // Map of requested files and the initial thread that got the request
    private HashMap<String, String> threadByFile;
    // Map of partitions each peer requested and the thread that works on serving them, (#12ae23, [peer1, {file1-1.txt, file1-3.txt}])
    private HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread;
    // Locks the threadByFile and peerPartitionsByThread
    private ReentrantLock lock;
    //Map of partitions that each peer has sent you (peer#123, {file1-1.txt, file4-3.txt})
    private ConcurrentHashMap<String, ArrayList<String>> partitionsByPeer;

    public Peer(String ip, int port, String shared_directory) {
        this.ip = ip;
        this.port = port;
        this.shared_directory = shared_directory;
        createFileDownloadList();
        //Here we may have to partition the completed files
        this.filesInNetwork = peersFilesInNetwork();
        this.seederOfFiles = seederOfFilesInNetwork();
        this.partitionsInNetwork = peersPartitionsInNetwork();
        this.isSeeder = this.seederOfFiles.size() > 0;
        this.isPeerOnline = false;
        this.threadByFile = new HashMap<>();
        this.peerPartitionsByThread = new HashMap<>();
        this.lock = new ReentrantLock();
        this.partitionsByPeer = new ConcurrentHashMap<String, ArrayList<String>>();
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
                        Thread t = new PeerServerThread(inConnection, this.filesInNetwork, this.partitionsInNetwork,
                                this.seederOfFiles, this.shared_directory, this.threadByFile,
                                this.peerPartitionsByThread, this.lock, this.partitionsByPeer, tokenID);
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
            sendTrackerInformationAsSeeder(this.tokenID);
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
            System.out.println("1) List\n2) Details\n3) Check Active\n4) Simple Download\n5) Collaborative Download\n6) LogOut\n");
            String option;
            // if the user gives invalid info then ask again
            do {
                System.out.print("> ");
                Scanner inp = new Scanner(System.in);
                option = inp.nextLine().toLowerCase().trim();
                if (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5") && !option.equals("6")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5") && !option.equals("6"));

            // manage options
            switch (option) {
                // option 1: list
                case "1":
                    list();
                    break;
                // option 2: details
                case "2":
                    details(null);
                    break;
                // option 3: check active
                case "3":
                    checkActive(null, 0);
                    break;
                // option 4: simple download
                case "4":
                    simpleDownload();
                    break;
                // option 4: collaborative download
                case "5":
                    collaborativeDownload();
                    break;
                // option 6: logout
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
    private ArrayList<Object> details(String filename) {
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
                    ArrayList<ArrayList<String>> fileOwnersPartitions = (ArrayList<ArrayList<String>>) in.readObject();
                    ArrayList<Boolean> fileOwnersSeederBit = (ArrayList<Boolean>) in.readObject();
                    int totalNumberOfParts = in.readInt();
                    System.out.println("Total number of parts: " + totalNumberOfParts);
                    System.out.println("Peer\t|" + "IP\t\t\t\t|" + "Port\t|" + "Username\t\t\t\t|" + "Downloads\t\t|" + "Fails\t\t\t|" + "Partitions\t\t|" + "Seeder\t");
                    for(int i=0; i<fileOwnersInfo.size(); i++){
                        //Maybe write somewhere what are the values we see. Preferable before this for
                        System.out.println("--------|---------------|-------|-----------------------|---------------|---------------|---------------|----------------");
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
                                System.out.print(fileOwnersStatistics.get(i)[j] + "\t".repeat(4 - (Integer.toString(fileOwnersStatistics.get(i)[j]).length() / 4)) + "|");
                        }
                        // owner partitions
                        System.out.print(fileOwnersPartitions.get(i).size() + "\t".repeat(4 - Integer.toString(fileOwnersPartitions.get(i).size()).length() / 4) + "|");
                        // is seeder for file
                        System.out.print(fileOwnersSeederBit.get(i) + "\t".repeat(4 - Boolean.toString(fileOwnersSeederBit.get(i)).length() / 4));
                        System.out.println();
                    }
                    System.out.println();

                    ArrayList<Object> result = new ArrayList<>();
                    result.add(fileOwnersInfo);
                    result.add(fileOwnersStatistics);
                    result.add(fileOwnersPartitions);
                    result.add(fileOwnersSeederBit);
                    result.add(totalNumberOfParts);

                    return result;
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
    private void simpleDownload(){
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
        ArrayList<Object> detailsResult = details(fileName);
        if (detailsResult == null) return;
        ArrayList<String[]> fileOwnersInfo = (ArrayList<String[]>) detailsResult.get(0);
        ArrayList<int[]> fileOwnersStatistics = (ArrayList<int[]>) detailsResult.get(1);

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
                    downloadFile(fileName, in);
                    notifyTracker(1, currentPeer, fileName);
                    this.filesInNetwork.add(fileName);
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

    /**
     * Option 5 | Collaborative Download
     * This method is called if the user asks for a collaborative download.
     * Goes through all the files in the fileDownloadList and tries to download every single one of them.
     * Here we download in partitions, so the peer will keep requesting from other peers part of the file, instead from whole.
     */
    private void collaborativeDownload() {
        // get this peer's info and statistics
        String[] myInfo = getName(this.tokenID);
        int[] myStats = getStatistics(this.tokenID);

        ArrayList<String> nonMatchingFiles = getNonMatchingFiles();
        int[] fileInspected = new int[nonMatchingFiles.size()];

        while (true) {
            String nextFile = select();
            if (nextFile.isEmpty()) {
                System.out.println("There are no other available partitions for any file");
                break;
            }
            if(Arrays.stream(fileInspected).sum()==nonMatchingFiles.size()){
                System.out.println("We tried to download every available file :)");
                break;
            }
            System.out.println("\nChoosing file: " + nextFile);

            //We inspect this file (either we are going to download it completely or some parts of it)
            // if we already inspected this file then skip it
            if (fileInspected[nonMatchingFiles.indexOf(nextFile)] != 1)
                fileInspected[nonMatchingFiles.indexOf(nextFile)] = 1;
            else
                continue;

            ArrayList<Object> detailsResult = details(nextFile);
            if (detailsResult == null) {
                System.out.println("There are no online peers with the file " + nextFile);
                continue;
            }

            // get file owners
            ArrayList<String[]> peersOwningFile = (ArrayList<String[]>) detailsResult.get(0);
            //ArrayList<int[]> peersOwningFileStats = (ArrayList<int[]>) detailsResult.get(1);
            //ArrayList<ArrayList<String>> peersOwningFilePartitions = (ArrayList<ArrayList<String>>) detailsResult.get(2);
            ArrayList<Boolean> peersOwningFileSeederBit = (ArrayList<Boolean>) detailsResult.get(3);
            int totalNumberOfParts = (int) detailsResult.get(4);

            boolean fileAssembled = false;
            while (!fileAssembled) {
                ArrayList<String[]> peersToRequestTo = new ArrayList<>();
                // we use this to keep track of the answers the peers give us
                // if we finally ask all the peers that own parts of this file and conclude that no one has a useful part
                // for us, then stop trying to download this file
                int[] peerOwningFileNoUsefulPartition = new int[peersOwningFile.size()];
                // mark ourselves with 1 already, of course
                for (int p = 0; p < peersOwningFile.size(); p++) {
                    if (peersOwningFile.get(p)[2].equals(myInfo[2])) {
                        peerOwningFileNoUsefulPartition[p] = 1;
                        break;
                    }
                }

                // if the peers owning parts of this file are more than 4, then select 4 random (at most 2 seeders)
                if (peersOwningFile.size() > 4) {
                    int requestToSeederCounter = 0; // we want at most 2 seeders
                    while (peersToRequestTo.size() < 4) {
                        int randIndex = new Random().nextInt(peersOwningFile.size());
                        // if we've already selected this peer or its ourselves then skip
                        if (peersToRequestTo.contains(peersOwningFile.get(randIndex)) || peersOwningFile.get(randIndex)[2].equals(myInfo[2])) continue;
                        // if the index shows to a seeder and the counter says we haven't selected more than 2 seeders yet,
                        // then add him to the peers we are going to send the request
                        if (peersOwningFileSeederBit.get(randIndex) && requestToSeederCounter < 2) {
                            peersToRequestTo.add(peersOwningFile.get(randIndex));
                            requestToSeederCounter++;
                        } else if (!peersOwningFileSeederBit.get(randIndex)) {
                            peersToRequestTo.add(peersOwningFile.get(randIndex));
                        }
                    }
                } else { // if the peers owning parts of this file are 4 or less, then select them all
                    ArrayList<String[]> toBeRemoved = new ArrayList<>();
                    peersToRequestTo.addAll(peersOwningFile);
                    //Remove self from peersToRequestTo
                    for(String[] peer : peersToRequestTo){
                        if(peer[2].equals(myInfo[2])){
                            toBeRemoved.add(peer);
                        }
                    }
                    peersToRequestTo.removeAll(toBeRemoved);
                    //We do it this way because String[] equals looks at memory
                }

                // get the partitions this peer owns for this file
                HashSet<String> partitionsOfFileOwned = new HashSet<>();
                // check if the peer owns at least one partition of this file, so we don't get exception
                if (this.filesInNetwork.contains(nextFile))
                    partitionsOfFileOwned.addAll(this.partitionsInNetwork.get(this.filesInNetwork.indexOf(nextFile)));

                // open connections with all the selected peers and send request
                int[] threadsFinished = new int[peersToRequestTo.size()]; // check which threads have finished
                for (int i = 0; i < peersToRequestTo.size(); i++) {
                    int finalI = i;
                    Thread peerRequestsThread = new Thread(() -> {
                        try {
                            System.out.println(Thread.currentThread().getName() + " I: " + finalI);
                            String[] peer = peersToRequestTo.get(finalI);
                            Socket connection = new Socket(peer[0], Integer.parseInt(peer[1]));
                            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
                            // write collaborative function code
                            out.writeInt(Function.COLLABORATIVE_DOWNLOAD_HANDLER.getEncoded());
                            // write file name
                            out.writeObject(nextFile);
                            // write the peer requesting the file
                            out.writeObject(myInfo[2]);
                            // write the partitions of this file this peer owns
                            out.writeObject(partitionsOfFileOwned);
                            // write the myStats of this peer
                            out.writeObject(myStats);
                            out.flush();

                            ObjectInputStream in = new ObjectInputStream(connection.getInputStream());
                            int result = in.readInt();
                            if (result == 0) {
                                System.out.println(peer[2] + " denied request.");
                            } else if (result == -1) {
                                // if the peer responds saying he doesn't own any useful files for us, then mark him as
                                // useless
                                System.out.println(peer[2] + "(" + Thread.currentThread().getName() + ") doesn't have any useful partitions for file " + nextFile);
                                int indexOfPeerOwningFile = -1;
                                for (int p = 0; p < peersOwningFile.size(); p++) {
                                    if (peersOwningFile.get(p)[2].equals(peer[2])) {
                                        indexOfPeerOwningFile = p;
                                        break;
                                    }
                                }
                                peerOwningFileNoUsefulPartition[indexOfPeerOwningFile] = 1;

                            } else {
                                String nameOfPartition = (String) in.readObject();
                                int sendBackPartitionCode = in.readInt();
                                // download file
                                downloadFile(nameOfPartition, in);
                                // notify tracker
                                notifyTracker(1, peer, nameOfPartition);
                                // update structs
                                this.filesInNetwork = peersFilesInNetwork();
                                this.partitionsInNetwork = peersPartitionsInNetwork();
                                // update the partitions sent by the peer we downloaded from
                                // if we have downloaded from this peer before, then just add the new partition we just downloaded
                                if (this.partitionsByPeer.containsKey(peer[2]))
                                    this.partitionsByPeer.get(peer[2]).add(nameOfPartition);
                                    // if we haven't downloaded from this peer before then create a new entry in the struct
                                else {
                                    ArrayList<String> value = new ArrayList<>();
                                    value.add(nameOfPartition);
                                    this.partitionsByPeer.put(peer[2], value);
                                }
                                // code for sending back one of this peer's partitions (if the other peer requested for it)
                                if (sendBackPartitionCode == 1) {
                                    // get the parts the other peer owns
                                    HashSet<String> partitionsOwnedByOtherPeer = (HashSet<String>) in.readObject();
                                    // create a set where we will store the parts this peer owns but the other peer does not
                                    ArrayList<String> candidatePartsToSend = new ArrayList<>();
                                    for (String partName : partitionsOfFileOwned) {
                                        if (!partitionsOwnedByOtherPeer.contains(partName)) {
                                            candidatePartsToSend.add(partName);
                                        }
                                    }
                                    // choose the part to send
                                    String partNameToSend = candidatePartsToSend.get(new Random().nextInt(candidatePartsToSend.size()));
                                    out.writeObject(partNameToSend);
                                    out.flush();
                                    sendFile(out, partNameToSend);
                                    // TODO: send the contents of this part - TEST IT
                                } else {
                                    System.out.println("OK NIBBA, I WILL NOT SEND BACK ANY FILES");
                                }
                            }
                            threadsFinished[finalI] = 1;
                        } catch (IOException | ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    peerRequestsThread.start();
                }
                // wait for all the threads to finish before moving on
                while (Arrays.stream(threadsFinished).sum() < threadsFinished.length) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                // TODO: if everyone rejects we call details from tracker after 500ms (from the last denial)
                // TODO: if from the parts the available peers own we are not missing any, then bye bye, break
                if (Arrays.stream(peerOwningFileNoUsefulPartition).sum() == peerOwningFileNoUsefulPartition.length) {
                    System.out.println("Asked all peers for file " + nextFile + " and no one has the partitions we need :(");
                    break;
                }

                // check if the file is whole
                if (this.filesInNetwork.contains(nextFile) && this.partitionsInNetwork.get(this.filesInNetwork.indexOf(nextFile)).size() == totalNumberOfParts) {
                    fileAssembled = true;
                    assembleFile(nextFile, totalNumberOfParts);
                    this.seederOfFiles = seederOfFilesInNetwork();
                    sendTrackerInformationAsSeeder(this.tokenID);
                }
            }
        }
    }

    /**
     * Option 6 | User LogOut
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
     * Download a file.
     * @param fileName The name of the file.
     * @param in The input stream from which we will read the bytes.
     */
    private void downloadFile(String fileName, ObjectInputStream in) {
        try {
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
            fileOutputStream.close();

            // notify tracker for successful download
            System.out.println("File received successfully.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A formula for calculating the "goodness" of a peer.
     * @param countDownloads How many total successful downloads he has offered.
     * @param countFailures How many failed downloads he has offered.
     * @return The result of the function.
     */
    private double priorityFormula(int countDownloads, int countFailures) {
        return Math.pow(0.75, countDownloads) * Math.pow(1.25, countFailures);
    }

    /**
     * When a file is downloaded this method notifies the tracker, so he can update his structures.
     * @param code 0 for fail to download, 1 for success
     * @param currentPeer The peer we downloaded from
     * @param fileName The name of the file we downloaded
     */
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
     * Send Tracker Information
     * This method is called after user log in to update tracker about the peer's information.
     * Send tokenID, files(Name because its unique), peerIP, peerPort to tracker so the tracker can store them.
     * @param token The tokenID of the peer assigned by the tracker.
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
            //Send partitions
            out.writeObject(partitionsInNetwork);
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
     * Send Tracker Information
     * This method is called after user log in to update tracker about the seeder's information.
     * Send tokenID, files(Name because its unique) and for each file all of its parts in a hashset.
     * @param token The tokenID of the peer assigned by the tracker.
     */
    private void sendTrackerInformationAsSeeder(int token) {
        if (!this.isSeeder) return;
        try {
            for (String file : seederOfFiles) {
                Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                //Send register code
                out.writeInt(Function.SEEDER_INFORM.getEncoded());
                out.flush();
                //Send tokenID
                out.writeInt(token);
                out.flush();
                //Send fileName
                out.writeObject(file);
                out.flush();
                //Send all the file parts
                int indexOfHashSet = this.filesInNetwork.indexOf(file);
                out.writeObject(this.partitionsInNetwork.get(indexOfHashSet));
                out.flush();
            }
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

            //save the common files - return
            // even if we don't have the full file and only own partitions of it we still add it to the data structure
            // how we do that is iterating through the fileDownloadList and for every file in there, if we find a file
            // in the shared directory which contains the name of the fileDownloadList file we add it.
            ArrayList<String> matchingFiles = new ArrayList<>();
            for(String file : filesInFileDownloadList) {
                // however we need to do a little trimming because of the extensions of the files.
                // ex. The value of file is file5.txt and in our shared directory we have file5-1.txt and file5-3.txt.
                // file5.txt is not contained in either of those two files we own (because of the .txt extension).
                // So we cut the .txt part, now file is just file5 and is contained in file5-1.txt for example, so
                // we know we want to add file5.txt. WARNING: FILES WITH '.' CHARACTER IN THEIR NAME WILL BE PROBLEMATIC.
                String fileTrimmed = file.substring(0, file.indexOf("."));
                for (String dirFile : filesInSharedDirectory) {
                    if (dirFile.contains(fileTrimmed)) {
                        matchingFiles.add(file);
                        break;
                    }
                }
            }
            return matchingFiles;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get each partition for every file of the filesInNetwork list.
     * @return An array of hashsets, each corresponding to the file at the same index of the array filesInNetwork.
     */
    private ArrayList<HashSet<String>> peersPartitionsInNetwork() {
        try {
            ArrayList<HashSet<String>> partitionsOfAllFiles = new ArrayList<>();

            //take the files that are in your directory
            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());

            for (int i=0; i<this.filesInNetwork.size(); i++) {
                HashSet<String> partitionsOfFile = new HashSet<>();
                String file = this.filesInNetwork.get(i);
                // trim the file's extension
                String fileTrimmed = file.substring(0, file.indexOf("."));
                // Iterate through all the files in the shared directory. If it contains fileTrimmed and is not the same as fileTrimmed
                // (meaning it is the whole file) then add to the hashset.
                for (String dirFile : filesInSharedDirectory) {
                    String dirFileTrimmed = dirFile.substring(0, dirFile.indexOf("."));
                    if (!dirFileTrimmed.equals(fileTrimmed) && dirFileTrimmed.contains(fileTrimmed)) {
                        partitionsOfFile.add(dirFile);
                    }
                }
                partitionsOfAllFiles.add(partitionsOfFile);
            }
            return partitionsOfAllFiles;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get all the files this peer is seeding.
     * @return An array of all the file names this peer is seeding
     */
    private ArrayList<String> seederOfFilesInNetwork() {
        try {
            ArrayList<String> seedingFiles = new ArrayList<>();

            //take the files that are in your directory
            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());

            for (String fileInNetwork : this.filesInNetwork) {
                String fileInNetworkTrimmed = fileInNetwork.substring(0, fileInNetwork.indexOf("."));
                for (String fileInShDir : filesInSharedDirectory) {
                    String fileInSHDirTrimmed = fileInShDir.substring(0, fileInShDir.indexOf("."));
                    if (fileInSHDirTrimmed.equals(fileInNetworkTrimmed)) {
                        seedingFiles.add(fileInShDir);
                        break;
                    }
                }
            }

            /*// split the seeding files
            for (String f : seedingFiles) {
                splitFile(f, Config.SPLIT_ASSEMBLE_SIZE);
            }*/

            return seedingFiles;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Split the file into multiple parts of size partLength
     * */
    private void splitFile(String fileName, int partLength){
        String fileToSendName = this.shared_directory + File.separator+fileName;
        String[] fileNameExtension = fileName. split("\\.");
        System.out.println("fileNameExtension: " + Arrays.toString(fileNameExtension));
        File fileToSplit = new File(fileToSendName);
        try(FileInputStream fileInputStream = new FileInputStream(fileToSplit);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)){

            byte[] filePartLength = new byte[partLength];
            int bytesRead;
            int partNumber = 1;

            while ((bytesRead = bufferedInputStream.read(filePartLength)) > 0) {
//                String pathToStorePart = String.format("%s-%d%s", fileBaseName, partNumber++, fileExtension);
                String pathToStorePart = "%s%s%s-%s.%s".formatted(this.shared_directory, File.separator, fileNameExtension[0], String.valueOf(partNumber), fileNameExtension[1]);
                partNumber++;
                FileOutputStream fileOutputStream = new FileOutputStream(pathToStorePart);
                // Delete existing data from the file
                fileOutputStream.getChannel().truncate(0);
                fileOutputStream.close(); // Close the file stream to ensure truncation takes effect
                //Open to write in the file
                fileOutputStream = new FileOutputStream(pathToStorePart, true);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
                bufferedOutputStream.write(filePartLength, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Assemble the separated parts of the file (fileName) into one.
     * If there are not all the files in the shared_directory (partsNumber) nothing happens.
     * Otherwise, create the file and store it into the shared_directory.
     * */
    private void assembleFile(String fileName, int partsNumber){
        String[] fileNameExtension = fileName. split("\\.");
        try {
            String pathToStore = this.shared_directory + File.separator + fileName;
            FileOutputStream fileOutputStream = new FileOutputStream(pathToStore);
            // Delete existing data from the file
            fileOutputStream.getChannel().truncate(0);
            fileOutputStream.close(); // Close the file stream to ensure truncation takes effect
            //Open to write in the file
            fileOutputStream = new FileOutputStream(pathToStore, true);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);

            for (int partNumber = 1; partNumber <= partsNumber; partNumber++) {
                String partFileName = "%s%s%s-%s.%s".formatted(this.shared_directory, File.separator, fileNameExtension[0], String.valueOf(partNumber), fileNameExtension[1]);
                File partFile = new File(partFileName);

                if (!partFile.exists()) {
                    System.out.println("Not all parts are downloaded.");
                    return;
                }

                try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(partFile))) {
                    byte[] buffer = new byte[Config.SPLIT_ASSEMBLE_SIZE];
                    int bytesRead;
                    while ((bytesRead = bufferedInputStream.read(buffer)) != -1) {
                        bufferedOutputStream.write(buffer, 0, bytesRead);
                    }
                }
            }
            bufferedOutputStream.close();

            System.out.println("File assembly completed successfully.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *  Find which files from the fileDownloadList this peer doesn't completely have in his shared directory
     * */
    private ArrayList<String> getNonMatchingFiles(){
        try {
            //Files in fileDownloadList
            List<String> filesInFileDownloadList = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader(this.shared_directory + File.separator + "fileDownloadList.txt"));
            String downloadableFile;
            while ((downloadableFile = reader.readLine()) != null) {
                // Add each line to the list
                filesInFileDownloadList.add(new String(downloadableFile));
            }
            //Files in sharedDirectory
            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());
            //Get the non conflicted
            ArrayList<String> nonMatchingFiles = new ArrayList<>();
            for(String file : filesInFileDownloadList){
                if(!filesInSharedDirectory.contains(file)){
                    nonMatchingFiles.add(file);
                }
            }
            return nonMatchingFiles;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Look in the fileDownloadList and the shared_directory
     * @return a random file from fileDownloadList that is not in the shared_directory- or Empty string if you have all the files
     * */
    protected String select(){
//        try {
//            //Files in fileDownloadList
//            List<String> filesInFileDownloadList = new ArrayList<>();
//            BufferedReader reader = new BufferedReader(new FileReader(this.shared_directory + File.separator + "fileDownloadList.txt"));
//            String downloadableFile;
//            while ((downloadableFile = reader.readLine()) != null) {
//                // Add each line to the list
//                filesInFileDownloadList.add(new String(downloadableFile));
//            }
//            System.out.println("filesInFileDownloadList");
//            filesInFileDownloadList.stream().forEach(System.out::println);
//            //Files in sharedDirectory
//            List<String> filesInSharedDirectory = Files.walk(Paths.get(this.shared_directory)).map(Path::getFileName).map(Path::toString).filter(n->n.endsWith(".txt")||n.endsWith(".png")).collect(Collectors.toList());
//            System.out.println("filesInSharedDirectory");
//            filesInSharedDirectory.stream().forEach(System.out::println);
//            //Get the non conflicted
//            ArrayList<String> nonMatchingFiles = new ArrayList<>();
//            for(String file : filesInFileDownloadList){
//                if(!filesInSharedDirectory.contains(file)){
//                    nonMatchingFiles.add(file);
//                }
//            }
//            System.out.println("nonMatchingFiles");
//            nonMatchingFiles.stream().forEach(System.out::println);
//            //Pick a random of those
//            if (!nonMatchingFiles.isEmpty()) {
//                Random random = new Random();
//                selectedFile = nonMatchingFiles.get(random.nextInt(nonMatchingFiles.size()));
//            }
//            //return it or empty
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
        String selectedFile = "";
        ArrayList<String> nonMatchingFiles = this.getNonMatchingFiles();
//        Pick a random of those
            if (!nonMatchingFiles.isEmpty()) {
                Random random = new Random();
                selectedFile = nonMatchingFiles.get(random.nextInt(nonMatchingFiles.size()));
            }
        return selectedFile;
    }

    /**
     * Get this peer's statistics for downloads and failures.
     * Send request to tracker and wait for response.
     * @param tokenID This user's tokenID
     * @return This user's statistics
     */
    private int[] getStatistics(int tokenID) {
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);

            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.writeInt(Function.REPLY_PEER_STATISTICS.getEncoded());
            out.writeInt(tokenID);
            out.flush();

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            int[] result = (int[]) in.readObject();
            in.close();
            out.close();
            socket.close();
            return result;

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Get this peer's username.
     * Send request to tracker and wait for response.
     * @param tokenID This user's tokenID
     * @return This user's username
     */
    private String[] getName(int tokenID) {
        try {
            // ask tracker for the info of the requesting peer with this tokenID
            Socket trackerConnectForPeerInfo = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            ObjectOutputStream trackerOut = new ObjectOutputStream(trackerConnectForPeerInfo.getOutputStream());
            trackerOut.writeInt(Function.SEND_PEER_INFO.getEncoded());
            trackerOut.writeInt(tokenID);
            trackerOut.flush();

            ObjectInputStream trackerIn = new ObjectInputStream(trackerConnectForPeerInfo.getInputStream());
            // get the peer info
            String[] peerInfo = (String[]) trackerIn.readObject();
            trackerIn.close();
            trackerOut.close();
            trackerConnectForPeerInfo.close();
            return peerInfo;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Option | Send file to peer.
     */
    private void sendFile(ObjectOutputStream out, String filename) {
        //initiate basic stream details
        String fileToSendName = this.shared_directory +File.separator+filename;
        File fileToSend = new File(fileToSendName);
        int numberOfPartsToSend = (int) Math.ceil((double) fileToSend.length() / Config.DOWNLOAD_SIZE);

        //use try-with-resources block to automatically close streams
        try (FileInputStream fileInputStream = new FileInputStream(fileToSend);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {

            //send number of parts to be sent in total
            out.writeInt(numberOfPartsToSend);
            out.flush();

            //create byte array of the file partition
            byte[] fileBytes = new byte[Config.DOWNLOAD_SIZE];


            //this loop will keep on reading partitions of partSize and send them to the receiver peer
            //until the byte stream has nothing more to read.
            //read method returns -1 when there are no more bytes to be read
            int i;
            while ((i = bufferedInputStream.read(fileBytes)) != -1) {
                //the read bytes are placed into the byte array fileBytes and sent to the receiver peer
                out.write(fileBytes, 0, i);
                out.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        // IP-Port-Shared_Directory Path
        Peer peer = new Peer(args[0], Integer.parseInt(args[1]), args[2]);
        // start the thread for the user
        Thread runPeer = new Thread(peer::runPeer);
        runPeer.start();
    }
}
