import misc.Config;
import misc.Function;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class PeerServerThread extends Thread {
    private ObjectInputStream in;
    private Socket connection;
    private ArrayList<String> filesInNetwork;
    private ArrayList<HashSet<String>> partitionsInNetwork;
    private ArrayList<String> seederOfFiles;
    private String shared_directory;
    // Map of requested files and the initial thread that got the request
    private HashMap<String, String> threadByFile;
    // Map of partitions each peer requested and the thread that works on serving them, (#12ae23, [peer1Connection, {file1-1.txt, file1-3.txt}])
    private HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread;
    // Locks the threadByFile and peerPartitionsByThread
    private ReentrantLock lock;
    //Map of partitions that each peer has sent you (peer#123, {file1-1.txt, file4-3.txt})
    private ConcurrentHashMap<String, ArrayList<String>> partitionsByPeer;
    // Map of socket connections -> peer username (socket12.obj, "peer1")
    private ConcurrentHashMap<Socket, String> peerUsernamesByConnection;
    private int tokenID;
    private boolean isSeeder;

    public PeerServerThread(Socket connection, ArrayList<String> filesInNetwork, ArrayList<HashSet<String>> partitionsInNetwork,
                            ArrayList<String> seederOfFiles, String shared_directory, HashMap<String, String> threadByFile,
                            HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread,
                            ReentrantLock lock, ConcurrentHashMap<String, ArrayList<String>> partitionsByPeer, ConcurrentHashMap<Socket, String> peerUsernamesByConnection,
                            int tokenID, boolean isSeeder) {
        //handle connection
        this.filesInNetwork = filesInNetwork;
        this.partitionsInNetwork = partitionsInNetwork;
        this.seederOfFiles = seederOfFiles;
        this.connection = connection;
        this.shared_directory = shared_directory;
        this.threadByFile = threadByFile;
        this.peerPartitionsByThread = peerPartitionsByThread;
        this.lock = lock;
        this.partitionsByPeer = partitionsByPeer;
        this.peerUsernamesByConnection = peerUsernamesByConnection;
        this.tokenID = tokenID;
        this.isSeeder = isSeeder;
        try {
            in = new ObjectInputStream(connection.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        try {
            // decide what to do based on the code sent
            int func = in.readInt();
            switch (func) {
                // checkActive
                case 10:
                    checkActive();
                    break;
                case 8:
                    handleSimpleDownload();
                    break;
                case 12:
                    collaborativeDownloadHandler();
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleSimpleDownload(){
        //check if file requested is available
        try {
            String filename = (String) in.readObject();
            ObjectOutputStream out = new ObjectOutputStream(this.connection.getOutputStream());
            //peer does not have the requested file
            if(!this.filesInNetwork.contains(filename)){
                sendResult(out,0);
            }

            sendResult(out,1);
            sendFile(out, filename);

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Handle the collaborative Download
     * */
    private void collaborativeDownloadHandler() {
        try {
            // get requesting peer file name
            String fileName = (String) in.readObject();
            // what kind of collaborative download to do, 0 means request, 1 means response after downloading
            int option = in.readInt();
            // get requesting peer username
            String peerUsername = (String) in.readObject();

            if (option == 1) {
                HashSet<String> partitionsOwnedByOtherPeer = (HashSet<String>) in.readObject();
                collaborativeDownloadResponse(fileName, partitionsOwnedByOtherPeer);
            }
            else if (option == 0) {
                // get the parts of the files that the user already has (so the other peers don't send already existing files)
                ArrayList<String> partitionsList = new ArrayList<>((HashSet<String>) in.readObject());
                // create the hashmap from the peer info and the partitions he said he owns for this file
                HashMap<String, ArrayList<String>> partitionsOwnedByPeer = new HashMap<>();
                partitionsOwnedByPeer.put(peerUsername, partitionsList);

                if(this.seederOfFiles.contains(fileName)) {
                    seederServe(fileName, partitionsOwnedByPeer);
                } else {
                    collaborativeDownload(fileName, partitionsOwnedByPeer);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Option | Send file to peer.
     */

    private void sendFile(ObjectOutputStream out, String filename) {
        String fileToSendName = this.shared_directory + File.separator + filename;
        File fileToSend = new File(fileToSendName);
        long fileSize = fileToSend.length();
        int bufferSize = 1024; // Buffer size (1 KB)
        int numberOfPartsToSend = (int) Math.ceil((double) fileSize / bufferSize);

        try (FileInputStream fileInputStream = new FileInputStream(fileToSend);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {

            // Send the number of parts to be sent
            out.writeInt(numberOfPartsToSend);
            out.flush();

            // Create byte array for file partition
            byte[] fileBytes = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = bufferedInputStream.read(fileBytes)) != -1) {
                // Send the size of the current part
                out.writeInt(bytesRead);
                // Send the actual bytes
                out.write(fileBytes, 0, bytesRead);
            }
            out.flush(); // Flush once at the end
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * Respond to the client's ping
     */
    private void checkActive() {
        try {
            int response = 1;

            // send response
            sendResult(new ObjectOutputStream(this.connection.getOutputStream()), response);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void seederServe(String fileName, HashMap<String, ArrayList<String>> partitionsOwnedByPeer) {
        try {
            // get the specific thread name
            String threadName = Thread.currentThread().getName();
            // get the requested partitions (all partitions - partitionsOwnedByPeer)
            HashMap<String, ArrayList<String>> partitionsReqByPeer = new HashMap<>();
            ArrayList<String> tempParts = new ArrayList<>();
            String peerName = "";
            for (String part : this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName))) {
                for (Map.Entry<String, ArrayList<String>> entry : partitionsOwnedByPeer.entrySet()) {
                    peerName = entry.getKey();
                    ArrayList<String> tab = entry.getValue();
                    if (!tab.contains(part))
                        tempParts.add(part);
                }
            }
            partitionsReqByPeer.put(peerName, tempParts);

            this.peerUsernamesByConnection.put(this.connection, peerName);

            //System.out.println("PARTITIONS REQUESTED BY PEER " + peerName + ": " + tempParts);

            // if the file name does not exit in the struct, add it and wait for 200ms for more potential requests for this file
            lock.lock();
            if (!this.threadByFile.containsKey(fileName)) {
                this.threadByFile.put(fileName, threadName);

                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                this.peerPartitionsByThread.put(threadName, innerMap);

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                synchronized (this.peerPartitionsByThread) {
                    this.threadByFile.remove(fileName);
                    HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                    //Convert involvedPeers to a list
                    //System.out.println("Involved peers (keyset): " + this.peerUsernamesByConnection);
                    List<Socket> involvedPeers = new ArrayList<>(partitionsRequestsPerPeer.keySet());
                    //Select a random peer
                    Socket selectedPeer = involvedPeers.get(ThreadLocalRandom.current().nextInt(0, involvedPeers.size()));
                    //System.out.println("Selected peer to send: " + this.peerUsernamesByConnection.get(selectedPeer));
                    //Retrieve the ArrayList of file partitions requested of the random peer
                    ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(selectedPeer);
                    //Select a random partition from the ArrayList
                    String selectedPart = requestedPartitions.get(ThreadLocalRandom.current().nextInt(0, requestedPartitions.size()));
                    //Get outputStream
                    ObjectOutputStream outputStream = new ObjectOutputStream(selectedPeer.getOutputStream());
                    //The codes can be changed to fit the method in the Peer!!!!!!!!!!!!!!!!!!!
                    //Send "OK" code - this can be removed
                    outputStream.writeInt(1);
                    outputStream.flush();
                    // send name of the part
                    outputStream.writeObject(selectedPart);
                    outputStream.flush();
                    // send the file
                    sendFile(outputStream, selectedPart);
                    try {
                        TimeUnit.MILLISECONDS.sleep(200);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    //Send "DENIED" to the rest
                    involvedPeers.remove(selectedPeer);
                    this.peerUsernamesByConnection.remove(selectedPeer);
                    for(Socket socket : involvedPeers) {
                        this.peerUsernamesByConnection.remove(socket);
                        outputStream = new ObjectOutputStream(socket.getOutputStream());
                        outputStream.writeInt(0);
                        outputStream.flush();
                    }
                }
            } else {
                synchronized (this.peerPartitionsByThread) {
                    // if the initial thread above got removed (because we waited 200ms and moved on in the if statement above),
                    // then ignore this request
                    if (!this.threadByFile.containsKey(fileName)) {
                        //inform him that we did not pick him
                        ObjectOutputStream outputStream = new ObjectOutputStream(this.connection.getOutputStream());
                        outputStream.writeInt(0);
                        outputStream.flush();
                    } else {
                        String initThread = this.threadByFile.get(fileName);

                        HashMap<Socket, ArrayList<String>> existingPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);
                        existingPeerPartitionsReq.put(this.connection, partitionsReqByPeer.get(peerName));
                        this.peerPartitionsByThread.put(initThread, existingPeerPartitionsReq);
                    }
                }
                lock.unlock();
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /***/
    private void collaborativeDownload(String fileName, HashMap<String, ArrayList<String>> partitionsOwnedByPeer) {
        try {
            // get the specific thread name
            String threadName = Thread.currentThread().getName();
            // get the requested partitions (all partitions - partitionsOwnedByPeer)
            HashMap<String, ArrayList<String>> partitionsReqByPeer = new HashMap<>();
            ArrayList<String> tempParts = new ArrayList<>();
            String peerName = "";
            for (String part : this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName))) {
                for (Map.Entry<String, ArrayList<String>> entry : partitionsOwnedByPeer.entrySet()) {
                    peerName = entry.getKey();
                    ArrayList<String> tab = entry.getValue();
                    if (!tab.contains(part))
                        tempParts.add(part);
                }
            }
            partitionsReqByPeer.put(peerName, tempParts);

            this.peerUsernamesByConnection.put(this.connection, peerName);

            lock.lock();
            if (!this.threadByFile.containsKey(fileName)) {
                this.threadByFile.put(fileName, threadName);

                //Testing
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                peerPartitionsByThread.put(threadName, innerMap);
                //Testing-end

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                synchronized (this.peerPartitionsByThread) {
                    this.threadByFile.get(fileName);
                    HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.get(threadName);
                    Socket selectedPeer = null;

                    if (partitionsRequestsPerPeer.size() == 1) {
                        //A
                        for (Socket socket : partitionsRequestsPerPeer.keySet()) {
                            selectedPeer = socket;
                            ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
                            //Retrieve the ArrayList of file partitions requested of the random peer
                            ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(socket);

                            // if we don't have any of the parts the other peer requested
                            HashSet<String> outPartitions = this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName));
                            ArrayList<String> requestedPartitionsWeHave = new ArrayList<>();

                            for(String part : requestedPartitions){
                                if (outPartitions.contains(part)){
                                    requestedPartitionsWeHave.add(part);
                                }
                            }

                            // if we don't have any of the parts the other peer requested
                            if (requestedPartitionsWeHave.isEmpty()) {
                                outputStream.writeInt(-1);
                                outputStream.flush();
                            } else {
                                // Select a random partition from the ArrayList
                                String selectedPart = requestedPartitionsWeHave.get(new Random().nextInt(requestedPartitionsWeHave.size()));
                                //Send "OK" code - this can be removed
                                outputStream.writeInt(1);
                                // send name of the part
                                outputStream.writeObject(selectedPart);
                                outputStream.flush();
                                //Send the selected part
                                sendFile(outputStream, selectedPart);
                                try {
                                    TimeUnit.MILLISECONDS.sleep(200);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    } else {
                        //B
                        int[] chanceBucket = {0, 0, 1, 1, 1, 1, 2, 2, 2, 2};
                        int randomIndex = new Random().nextInt(10);
                        int decision = chanceBucket[randomIndex];

                        switch (decision) {
                            case 0:
                                selectedPeer = randomPeerSelection(fileName, threadName, partitionsRequestsPerPeer);
                                break;
                            case 1:
                                selectedPeer = bestPeerFormula(fileName, threadName, partitionsRequestsPerPeer);
                                break;
                            case 2:
                                selectedPeer = peerWithMostSegmentsSent(fileName, threadName, partitionsRequestsPerPeer);
                                break;
                            default:
                                break;
                        }
                    }

                    this.threadByFile.remove(fileName);
                    this.peerPartitionsByThread.remove(threadName);

                    if (selectedPeer != null) {
                        String selectedPeerUsername = this.peerUsernamesByConnection.remove(selectedPeer);
                        HashSet<String> existingPartitions = this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName));

                        Socket askTrackerPeerInfo = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
                        // ask tracker for this peer's info
                        ObjectOutputStream tracker_out = new ObjectOutputStream(askTrackerPeerInfo.getOutputStream());
                        tracker_out.writeInt(Function.SEND_OTHER_PEER_INFO.getEncoded());
                        tracker_out.writeObject(selectedPeerUsername);
                        tracker_out.flush();
                        ObjectInputStream tracker_in = new ObjectInputStream(askTrackerPeerInfo.getInputStream());
                        String[] selectedPeerInfo = (String[]) tracker_in.readObject();

                        // ask tracker for the file info
                        Socket askTrackerFileInfo = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
                        ObjectOutputStream tracker_out2 = new ObjectOutputStream(askTrackerFileInfo.getOutputStream());
                        tracker_out2.writeInt(Function.REPLY_DETAILS.getEncoded());
                        tracker_out2.writeInt(this.tokenID);
                        tracker_out2.writeObject(fileName);
                        tracker_out2.flush();
                        ObjectInputStream tracker_in2 = new ObjectInputStream(askTrackerFileInfo.getInputStream());
                        tracker_in2.readInt();
                        tracker_in2.readObject();
                        tracker_in2.readObject();
                        tracker_in2.readObject();
                        tracker_in2.readObject();
                        int totalNumberOfParts = tracker_in2.readInt();

                        Socket askingBackSocket = new Socket(selectedPeerInfo[0], Integer.parseInt(selectedPeerInfo[1]));
                        ObjectOutputStream outputStream = new ObjectOutputStream(askingBackSocket.getOutputStream());
                        //collaborative download code
                        outputStream.writeInt(Function.COLLABORATIVE_DOWNLOAD_HANDLER.getEncoded());
                        //requested file name
                        outputStream.writeObject(fileName);
                        // option for collaborative download handler
                        outputStream.writeInt(1);
                        //username
                        outputStream.writeObject(Peer.lastUsedUsername);
                        //our existing partitions
                        outputStream.writeObject(existingPartitions);
                        outputStream.flush();

                        // wait for response
                        System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " requested a file from selected peer: " + selectedPeerUsername);
                        ObjectInputStream inputStream = new ObjectInputStream(askingBackSocket.getInputStream());
                        int result = inputStream.readInt();
                        if (result == -1) {
                            // if the peer responds saying he doesn't own any useful files for us
                            System.out.println("[" + Thread.currentThread().getName() + "] " + selectedPeerUsername + " doesn't have any useful partitions for file " + fileName);
                        } else {
                            String nameOfPartition = (String) inputStream.readObject();
                            // download file
                            downloadFile(nameOfPartition, inputStream);
                            notifyTracker(1, selectedPeerInfo, nameOfPartition);
                            System.out.println("[" + Thread.currentThread().getName() + "] " + "Successfully downloaded and notified tracker for file " + nameOfPartition + " from peer " + selectedPeerUsername);
                            this.filesInNetwork = peersFilesInNetwork();
                            this.partitionsInNetwork = peersPartitionsInNetwork();
                            // update the partitions sent by the peer we downloaded from
                            // if we have downloaded from this peer before, then just add the new partition we just downloaded
                            if (this.partitionsByPeer.containsKey(selectedPeerInfo[2])) {
                                this.partitionsByPeer.get(selectedPeerInfo[2]).add(nameOfPartition);
                            }
                            // if we haven't downloaded from this peer before then create a new entry in the struct
                            else {
                                ArrayList<String> value = new ArrayList<>();
                                value.add(nameOfPartition);
                                this.partitionsByPeer.put(selectedPeerInfo[2], value);
                            }

                            // check if the file is whole
                            if (this.filesInNetwork.contains(fileName) && this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName)).size() == totalNumberOfParts) {
                                assembleFile(fileName, totalNumberOfParts);
                                this.seederOfFiles = seederOfFilesInNetwork();
                                sendTrackerInformationAsSeeder(this.tokenID);
                            }
                        }

                    }
                }

            } else {
                synchronized (this.peerPartitionsByThread) {
                    // if the initial thread above got removed (because we waited 200ms and moved on in the if statement above),
                    // then ignore this request
                    if (!this.threadByFile.containsKey(fileName)) {
                        //inform him that we did not pick him
                        ObjectOutputStream outputStream = new ObjectOutputStream(this.connection.getOutputStream());
                        outputStream.writeInt(0);
                        outputStream.flush();
                    }else{
                        String initThread = this.threadByFile.get(fileName);

                        HashMap<Socket, ArrayList<String>> existingPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);
                        existingPeerPartitionsReq.put(this.connection, partitionsReqByPeer.get(peerName));
                        this.peerPartitionsByThread.put(initThread, existingPeerPartitionsReq);
                        for (Map.Entry<String, HashMap<Socket, ArrayList<String>>> entry : this.peerPartitionsByThread.entrySet()) {
                            int i=1;
                            for (Map.Entry<Socket, ArrayList<String>> entry2 : entry.getValue().entrySet()) {
                                System.out.println(i + ". Peer: " + this.peerUsernamesByConnection.get(entry2.getKey()) + " | Parts: " + entry2.getValue());
                                i++;
                            }
                        }
                    }
                }
                lock.unlock();
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (InterruptedException | IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * case 0 - Randomly select a peer
     * */
    private Socket randomPeerSelection(String fileName, String threadName, HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer) {
        //find all peers who requested segments of the specific file - filename
        HashSet<Socket> peerRequestersSet = new HashSet<>();

        for (String thread : this.peerPartitionsByThread.keySet()) {
            if (thread.equals(threadName)) {
                HashMap<Socket, ArrayList<String>> partsPerSocket = this.peerPartitionsByThread.get(thread);
                peerRequestersSet.addAll(partsPerSocket.keySet());
                break;
            }
        }

        ArrayList<Socket> peerRequesters = new ArrayList<>(peerRequestersSet);

        //select randomly a peer
        Socket selectedPeer = peerRequesters.get(ThreadLocalRandom.current().nextInt(peerRequesters.size()));
        String peerUsername = this.peerUsernamesByConnection.get(selectedPeer);
        peerRequesters.remove(selectedPeer);

        ObjectOutputStream out;

        // notify all other requesting peers about their failure of choice
        for(Socket peer : peerRequesters){
            try {
                this.peerUsernamesByConnection.remove(peer);
                out = new ObjectOutputStream(peer.getOutputStream());
                out.writeInt(0);
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            out = new ObjectOutputStream(selectedPeer.getOutputStream());
            //find the requested partitions by the random peer
            ArrayList<String> randomPeerRequestedParts = partitionsRequestsPerPeer.get(selectedPeer);
            ArrayList<String> ourParts = new ArrayList<>(this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName)));
            ArrayList<String> candidateParts = new ArrayList<>();

            for (String part : randomPeerRequestedParts) {
                if (ourParts.contains(part))
                    candidateParts.add(part);
            }

            // if there are no useful partitions to send then send code -1
            if (candidateParts.isEmpty()) {
                out.writeInt(-1);
                out.flush();
                return null;
            }

            //select a random requested partition to send to random peer
            int randIndex = ThreadLocalRandom.current().nextInt(0, candidateParts.size());
            String selectedPartitionSend = candidateParts.get(randIndex);

            //send this partition to the random peer
            out.writeInt(1);
            out.writeObject(selectedPartitionSend);
            out.flush();
            sendFile(out, selectedPartitionSend);
            TimeUnit.MILLISECONDS.sleep(200);
            System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " sent a file (" + selectedPartitionSend + ")" + " to randomly selected peer: " + peerUsername);

            return selectedPeer;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * case 1 - Select the best peer
     * */
    private Socket bestPeerFormula(String filename, String threadName, HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer) {
        //find all peers who requested segments of the specific file - filename
        HashSet<Socket> peerRequestersSet = new HashSet<>();

        for (String thread : this.peerPartitionsByThread.keySet()) {
            if (thread.equals(threadName)) {
                HashMap<Socket, ArrayList<String>> partsPerSocket = this.peerPartitionsByThread.get(thread);
                peerRequestersSet.addAll(partsPerSocket.keySet());
                break;
            }
        }

        System.out.println("Sent request: " + peerRequestersSet);

        // cast hashset to arraylist, so we can serially get each peer
        ArrayList<Socket> peerRequesters = new ArrayList<>(peerRequestersSet);
        // array where we will store each peers statistics (download count, download fails)
        ArrayList<int[]> stats = new ArrayList<>();

        // get each peer's stats
        for (Socket peerConnection : peerRequesters) {
            try {
                String peerUsername = this.peerUsernamesByConnection.get(peerConnection);
                Socket trackerConnect = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
                ObjectOutputStream trackerOut = new ObjectOutputStream(trackerConnect.getOutputStream());
                trackerOut.writeInt(Function.REPLY_PEER_STATISTICS.getEncoded());
                trackerOut.writeObject(peerUsername);
                trackerOut.flush();
                ObjectInputStream trackerIn = new ObjectInputStream(trackerConnect.getInputStream());
                int[] peerStats = (int[]) trackerIn.readObject();
                stats.add(peerStats);
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        // choose the better peer
        int betterPeerIndex = 0;
        double prevResult = 0;
        for (int i = 0; i < peerRequesters.size(); i++) {
            double result = priorityFormula(stats.get(i)[0], stats.get(i)[1]);
            if (result > prevResult) {
                prevResult = result;
                betterPeerIndex = i;
            }
        }

        // get the best peer based on the bestPeerIndex
        Socket bestPeer = peerRequesters.get(betterPeerIndex);
        String peerUsername = this.peerUsernamesByConnection.get(bestPeer);
        peerRequesters.remove(bestPeer);

        ObjectOutputStream out;

        // notify all other requesting peers about their failure of choice
        peerRequesters.remove(bestPeer);
        for(Socket peer : peerRequesters){
            try {
                this.peerUsernamesByConnection.remove(peer);
                out = new ObjectOutputStream(peer.getOutputStream());
                out.writeInt(0);
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            out = new ObjectOutputStream(bestPeer.getOutputStream());
            //find the requested partitions by the best peer
            ArrayList<String> bestPeerRequestedParts = partitionsRequestsPerPeer.get(bestPeer);
            ArrayList<String> ourParts = new ArrayList<>(this.partitionsInNetwork.get(this.filesInNetwork.indexOf(filename)));
            ArrayList<String> candidateParts = new ArrayList<>();

            for (String part : bestPeerRequestedParts) {
                if (ourParts.contains(part))
                    candidateParts.add(part);
            }

            // if there are no useful partitions to send then send code -1
            if (candidateParts.isEmpty()) {
                out.writeInt(-1);
                out.flush();
                return null;
            }

            //select a random requested partition to send to best peer
            int randIndex = ThreadLocalRandom.current().nextInt(0, candidateParts.size());
            String selectedPartitionSend = candidateParts.get(randIndex);

            //send this partition to the best peer
            out.writeInt(1);
            out.writeObject(selectedPartitionSend);
            out.flush();
            sendFile(out, selectedPartitionSend);
            TimeUnit.MILLISECONDS.sleep(200);
            System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " sent a file (" + selectedPartitionSend + ")" + " to best peer: " + peerUsername);

            return bestPeer;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * case 2 - Select peer who has sent the most parts so far
     * */
    private Socket peerWithMostSegmentsSent(String filename, String threadName, HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer) {
        //find all peers who requested segments of the specific file - filename
        HashSet<Socket> peerRequesters = new HashSet<>();

        for (String thread : this.peerPartitionsByThread.keySet()) {
            if (thread.equals(threadName)) {
                HashMap<Socket, ArrayList<String>> partsPerSocket = this.peerPartitionsByThread.get(thread);
                peerRequesters.addAll(partsPerSocket.keySet());
                break;
            }
        }


        //find requesting peer with the most segments sent to us
        Socket maxPeer = null;
        int maxCount = -1;
        for(Socket peer : peerRequesters) {
            if (!partitionsByPeer.contains(peerUsernamesByConnection.get(peer))) {
                if (maxPeer == null) {
                    maxPeer = peer;
                    maxCount = 0;
                }
                continue;
            }
            int currentSegmentCount = partitionsByPeer.get(peerUsernamesByConnection.get(peer)).size();
            if(currentSegmentCount > maxCount) {
                maxPeer = peer;
                maxCount = currentSegmentCount;
            }
            else if(currentSegmentCount == maxCount){
                try {
                    //connect to tracker and get count download/fail to determine max peer in case of equality
                    Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    out.writeInt(Function.REPLY_DETAILS.getEncoded());
                    out.writeInt(tokenID);
                    out.writeObject(filename);

                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    //read data from tracker
                    in.readInt();
                    ArrayList<String[]> fileOwnersInfo = (ArrayList<String[]>) in.readObject();
                    ArrayList<int[]> fileOwnersStatistics = (ArrayList<int[]>) in.readObject();
                    //skip unneeded data
                    in.readObject();
                    in.readObject();
                    in.readInt();


                    //find current and max peers count fail/download
                    String maxPeerUsername = peerUsernamesByConnection.get(maxPeer);
                    String curPeerUsername = peerUsernamesByConnection.get(peer);
                    int maxPeerIndex = -1;
                    int curPeerIndex = -1;
                    for(int i=0; i<fileOwnersInfo.size(); i++){
                        if(maxPeerIndex<0 && fileOwnersInfo.get(i)[2].equals(maxPeerUsername)){
                            maxPeerIndex = i;
                        }
                        if(curPeerIndex<0 && fileOwnersInfo.get(i)[2].equals(curPeerUsername)){
                            curPeerIndex = i;
                        }
                    }

                    double maxPeerScore = priorityFormula(fileOwnersStatistics.get(maxPeerIndex)[1], fileOwnersStatistics.get(maxPeerIndex)[0]);
                    double curPeerScore = priorityFormula(fileOwnersStatistics.get(curPeerIndex)[1], fileOwnersStatistics.get(curPeerIndex)[0]);

                    if(maxPeerScore<=curPeerScore){
                        maxPeer = peer;
                    }

                } catch (IOException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        String peerUsername = this.peerUsernamesByConnection.get(maxPeer);
        peerRequesters.remove(maxPeer);

        ObjectOutputStream out;
        //notify all other requesting peers about their failure of choice
        for(Socket peer : peerRequesters){
            try {
                this.peerUsernamesByConnection.remove(peer);
                out = new ObjectOutputStream(peer.getOutputStream());
                out.writeInt(0);
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            ObjectOutputStream outputStream = new ObjectOutputStream(maxPeer.getOutputStream());
            //find the requested partitions by the max peer
            System.out.println("Max peer: " + maxPeer);
            ArrayList<String> maxPeerRequestedParts = partitionsRequestsPerPeer.get(maxPeer);
            ArrayList<String> ourParts = new ArrayList<>(this.partitionsInNetwork.get(this.filesInNetwork.indexOf(filename)));
            ArrayList<String> candidateParts = new ArrayList<>();

            for (String part : maxPeerRequestedParts) {
                if (ourParts.contains(part))
                    candidateParts.add(part);
            }

            // if there are no useful partitions to send then send code -1
            if (candidateParts.isEmpty()) {
                outputStream.writeInt(-1);
                outputStream.flush();
                return null;
            }

            //select a random requested partition to send to max peer
            int randIndex = ThreadLocalRandom.current().nextInt(0, candidateParts.size());
            String selectedPartitionSend = candidateParts.get(randIndex);

            //send this partition to the max peer
            outputStream.writeInt(1);
            outputStream.writeObject(selectedPartitionSend);
            outputStream.flush();
            sendFile(outputStream, selectedPartitionSend);
            TimeUnit.MILLISECONDS.sleep(200);
            System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " sent a file (" + selectedPartitionSend + ")" + " to max peer: " + peerUsername);

            return maxPeer;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void collaborativeDownloadResponse(String fileName, HashSet<String> partitionsOwnedByOtherPeer) {
        try {
            HashSet<String> partitionsOfFileOwned = this.partitionsInNetwork.get(this.filesInNetwork.indexOf(fileName));
            // create a set where we will store the parts this peer owns but the other peer does not
            ArrayList<String> candidatePartsToSend = new ArrayList<>();
            for (String partName : partitionsOfFileOwned) {
                if (!partitionsOwnedByOtherPeer.contains(partName)) {
                    candidatePartsToSend.add(partName);
                }
            }

            ObjectOutputStream out = new ObjectOutputStream(this.connection.getOutputStream());

            // if there are no useful partitions to send then send code -1
            if (candidatePartsToSend.isEmpty()) {
                out.writeInt(-1);
                out.flush();
            } else {
                // choose the part to send
                String partNameToSend = candidatePartsToSend.get(new Random().nextInt(candidatePartsToSend.size()));
                out.writeInt(1);
                out.writeObject(partNameToSend);
                out.flush();
                sendFile(out, partNameToSend);
                TimeUnit.MILLISECONDS.sleep(200);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private double priorityFormula(int countDownloads, int countFailures) {
        return Math.pow(0.75, countDownloads) * Math.pow(1.25, countFailures);
    }

    private void sendResult(ObjectOutputStream out, Object r) {
        try {
            out.writeObject(r);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================================================================
    // FROM THIS POINT AND ON THE METHODS ARE THE SAME WITH PEER'S CLASS METHODS (SHIT CODE BUT WHAT CAN YOU DO, CHANGE LATER)
    // ================================================================================================================


    private void downloadFile(String fileName, ObjectInputStream in) {
        try {
            String pathToStore = this.shared_directory + File.separator + fileName;
            // Open to write in the file, truncating it first
            try (FileOutputStream fileOutputStream = new FileOutputStream(pathToStore);
                 BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream)) {

                // Read number of file parts to be received
                int fileParts = in.readInt();
                for (int i = 0; i < fileParts; i++) {
                    // Read the size of the current part
                    int partSize = in.readInt();
                    byte[] fileBytes = new byte[partSize];

                    // Read the actual bytes
                    int bytesRead = 0;
                    while (bytesRead < partSize) {
                        int read = in.read(fileBytes, bytesRead, partSize - bytesRead);
                        if (read == -1) {
                            throw new IOException("Unexpected end of stream.");
                        }
                        bytesRead += read;
                    }

                    // Write the bytes to the file
                    bufferedOutputStream.write(fileBytes, 0, bytesRead);
                }
                bufferedOutputStream.flush(); // Ensure all data is written
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

            return seedingFiles;

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
}