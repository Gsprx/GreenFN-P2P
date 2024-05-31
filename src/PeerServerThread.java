import misc.Config;
import misc.Function;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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

    public PeerServerThread(Socket connection, ArrayList<String> filesInNetwork, ArrayList<HashSet<String>> partitionsInNetwork,
                            ArrayList<String> seederOfFiles, String shared_directory, HashMap<String, String> threadByFile,
                            HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread,
                            ReentrantLock lock, ConcurrentHashMap<String, ArrayList<String>> partitionsByPeer, int tokenID) {
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
        this.peerUsernamesByConnection = new ConcurrentHashMap<>();
        this.tokenID = tokenID;
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
            connection.close();
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
    /**
     * Respond to the client's ping
     */
    private void checkActive() {
        try {
            int response = 1;

            // send response
            sendResult(new ObjectOutputStream(connection.getOutputStream()), response);

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

            for (String key : partitionsReqByPeer.keySet()) {
                this.peerUsernamesByConnection.put(this.connection, key);
            }

            System.out.println("PARTITIONS REQUESTED BY PEER " + peerName + ": " + tempParts);

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
                TimeUnit.MILLISECONDS.sleep(3000);
                synchronized (this.peerPartitionsByThread) {
                    synchronized (this.threadByFile) {
                        this.threadByFile.remove(fileName);
                        HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                        //Convert involvedPeers to a list
                        List<Socket> involvedPeers = new ArrayList<>(partitionsRequestsPerPeer.keySet());
                        for (Socket socket : involvedPeers) {
                            System.out.println("Involved peers: " + this.peerUsernamesByConnection.get(socket));
                        }
                        //Select a random peer
                        Socket selectedPeer = involvedPeers.get(ThreadLocalRandom.current().nextInt(0, involvedPeers.size()));
                        System.out.println("Selected peer to send: " + this.peerUsernamesByConnection.get(selectedPeer));
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
                        // TODO: wait until the other peer has read all the contents of the file, and then we can close the socket
                        // TODO: without any problems
                        try {
                            TimeUnit.MILLISECONDS.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        //Send "DENIED" to the rest
                        involvedPeers.remove(selectedPeer);
                        this.peerUsernamesByConnection.remove(selectedPeer);
                        for(Socket socket : involvedPeers) {
                            System.out.println("Socket: " + socket);
                            this.peerUsernamesByConnection.remove(socket);
                            outputStream = new ObjectOutputStream(socket.getOutputStream());
                            outputStream.writeInt(0);
                            outputStream.flush();
                        }
                        //Testing-end
                    }
                }
            } else {
                synchronized (this.peerPartitionsByThread) {
                    synchronized (this.threadByFile) {
                        // if the initial thread above got removed (because we waited 200ms and moved on in the if statement above),
                        // then ignore this request
                        if (!this.threadByFile.containsKey(fileName)) {
                            lock.unlock();
                            return;
                        }
                        String initThread = this.threadByFile.get(fileName);

                        HashMap<Socket, ArrayList<String>> existingPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);
                        existingPeerPartitionsReq.put(this.connection, partitionsReqByPeer.get(this.peerUsernamesByConnection.get(this.connection)));
                        this.peerPartitionsByThread.put(initThread, existingPeerPartitionsReq);

                        lock.unlock();
                    }
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

            lock.lock();
            if (!this.threadByFile.containsKey(fileName)) {
                this.threadByFile.put(fileName, threadName);
                for (String key : partitionsReqByPeer.keySet()) {
                    this.peerUsernamesByConnection.put(this.connection, key);
                }

                //Testing
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                peerPartitionsByThread.put(threadName, innerMap);
                //Testing-end

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                lock.lock();
                this.threadByFile.remove(fileName);
                //Testing
                HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                //Testing-end
                lock.unlock();

                if (partitionsRequestsPerPeer.size() == 1) {
                    //A
                    for (Socket socket : partitionsRequestsPerPeer.keySet()) {
                        ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
                        //Retrieve the ArrayList of file partitions requested of the random peer
                        ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(socket);
                        // if we don't have any of the parts the other peer requested
                        if (requestedPartitions.isEmpty()) {
                            outputStream.writeInt(-1);
                            outputStream.flush();
                        } else {
                            // Select a random partition from the ArrayList
                            String selectedPart = requestedPartitions.get(new Random().nextInt(requestedPartitions.size()));
                            //Send "OK" code - this can be removed
                            outputStream.writeInt(1);
                            outputStream.flush();
                            // send name of the part
                            outputStream.writeObject(selectedPart);
                            outputStream.flush();
                            //Send the selected part
                            sendFile(outputStream, selectedPart);
                            // TODO: wait until the other peer has read all the contents of the file, and then we can close the socket
                            // TODO: without any problems
                            try {
                                TimeUnit.MILLISECONDS.sleep(1000);
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
                    decision = 2;

                    switch (decision) {
                        case 0:
                            break;
                        case 1:
                            bestPeerFormula(fileName, threadName);
                            break;
                        case 2:
                            peerWithMostSegmentsSent(fileName, partitionsRequestsPerPeer);
                            break;
                        default:
                            break;
                    }
                }

            } else {
                String initThread = this.threadByFile.get(fileName);

                //Testing
                HashMap<Socket, ArrayList<String>> newPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                newPeerPartitionsReq.putAll(innerMap);
                this.peerPartitionsByThread.put(initThread,newPeerPartitionsReq);
                //Testing-end
                lock.unlock();
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bestPeerFormula(String filename, String threadName) {
        //find all peers who requested segments of the specific file - filename
        HashSet<Socket> peerRequestersSet = new HashSet<>();

        for (String thread : this.peerPartitionsByThread.keySet()) {
            if (thread.equals(threadName)) {
                HashMap<Socket, ArrayList<String>> partsPerSocket = this.peerPartitionsByThread.get(thread);
                peerRequestersSet.addAll(partsPerSocket.keySet());
                break;
            }
        }

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

            /*try {
                ObjectInputStream peerInput = new ObjectInputStream(peerConnection.getInputStream());
                stats.add((int[]) peerInput.readObject());

            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }*/
        }

        // choose the better peer
        int betterPeerIndex = -1;
        double prevResult = -1;
        for (int i = 0; i < peerRequesters.size(); i++) {
            double result = priorityFormula(stats.get(i)[0], stats.get(i)[1]);
            if (result > prevResult) {
                prevResult = result;
                betterPeerIndex = i;
            }
        }

        // TODO: Send appropriate file part and request one as well (if needed)
    }

    private void peerWithMostSegmentsSent(String filename, HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer) {
        //find all peers who requested segments of the specific file - filename
        HashSet<Socket> peerRequesters = new HashSet<>();
        
        //TODO: Try to optimize data structs to avoid 3D for loop
        for (HashMap<Socket, ArrayList<String>> socketSegmentsHashMap : peerPartitionsByThread.values()) {
            for(Socket peer : socketSegmentsHashMap.keySet()){
                for(String segment : socketSegmentsHashMap.get(peer)){
                    if (segment.contains(filename)){
                        peerRequesters.add(peer);
                    }
                }
            }
        }


        //find requesting peer with the most segments sent to us
        Socket maxPeer = null;
        int maxCount = 0;
        for(Socket peer : peerRequesters){
            int currentSegmentCount = partitionsByPeer.get(peerUsernamesByConnection.get(peer)).size();
            if(currentSegmentCount > maxCount){
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

        ObjectOutputStream out;
        //notify all other requesting peers about their failure of choice
        for(Socket peer : peerRequesters){
            if(peer.equals(maxPeer)){
                continue;
            }
            try {
                out = new ObjectOutputStream(peer.getOutputStream());
                out.writeInt(0);
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        //check if we are missing any segments for this file using the tracker
        try {
            Socket socket = new Socket(Config.TRACKER_IP, Config.TRACKER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.writeInt(Function.REPLY_DETAILS.getEncoded());
            out.writeInt(tokenID);
            out.writeObject(filename);
            out.flush();

            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            //read data from tracker
            in.readInt();
            //skip unneeded data
            in.readObject();
            in.readObject();
            //keep data we want to use
            ArrayList<ArrayList<String>> fileOwnersPartitions = (ArrayList<ArrayList<String>>) in.readObject();
            ArrayList<Boolean> fileOwnersSeederBit = (ArrayList<Boolean>) in.readObject();
            //use max number of segments to know if we are missing any segments
            int maxSegments = in.readInt();

            boolean missingSegments = false;
            int fileIndex = -1;
            HashSet<String> existingPartitions = new HashSet<>();

            //scan relevant entries in partitions in network datastruct
            for(int i =0; i<filesInNetwork.size(); i++){
                if(filesInNetwork.get(i).equals(filename)){
                    fileIndex = i;
                    if(partitionsInNetwork.get(i).size()<maxSegments) {
                        missingSegments = true;
                    }
                    //create a list with all our existing partitions
                    existingPartitions.addAll(partitionsInNetwork.get(fileIndex));
                }
            }

            if(missingSegments) {
                //keep all known segment names in a set to compare with local segments available
                HashSet<String> segmentNames = new HashSet<>();
                for (ArrayList<String> fileOwnersPartition : fileOwnersPartitions) {
                    segmentNames.addAll(fileOwnersPartition);
                }

                //create a list with all the missing partitions
                ArrayList<String> missingPartitions = new ArrayList<>();
                for(String partition : segmentNames){
                    if(!partitionsInNetwork.get(fileIndex).contains(partition)){
                        missingPartitions.add(partition);
                    }
                }
                //find the requested partitions by the max peer
                ArrayList<String> maxPeerRequestedParts = partitionsRequestsPerPeer.get(maxPeer);

                //select a random requested partition to send to max peer
                int randIndex = ThreadLocalRandom.current().nextInt(0,maxPeerRequestedParts.size());
                String selectedPartitionSend = maxPeerRequestedParts.get(randIndex);

                //send this partition to the max peer
                sendFile(new ObjectOutputStream(maxPeer.getOutputStream()), selectedPartitionSend);
                System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " sent a file (" + selectedPartitionSend + ")" + " to max peer: " + peerUsernamesByConnection.get(maxPeer));


                //select a random partition to request
                /*randIndex = ThreadLocalRandom.current().nextInt(0,missingPartitions.size());
                String selectedPartitionRequest = missingPartitions.get(randIndex);*/

                //request missing segments from max peer
                out = new ObjectOutputStream(maxPeer.getOutputStream());
                //collabDownload code
                out.writeInt(Function.COLLABORATIVE_DOWNLOAD_HANDLER.getEncoded());
                //requested file name
                out.writeObject(filename);
                // option for collaborative download handler
                out.writeInt(1);
                //username
                out.writeObject(Peer.lastUsedUsername);
                //our existing partitions
                out.writeObject(existingPartitions);
                out.flush();
                System.out.println("[CollaborativeDownload] Token ID: " + tokenID + " requested a file from max peer: " + peerUsernamesByConnection.get(maxPeer));
            }

        } catch (IOException | ClassNotFoundException e) {
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
            // choose the part to send
            ObjectOutputStream out = new ObjectOutputStream(this.connection.getOutputStream());
            String partNameToSend = candidatePartsToSend.get(new Random().nextInt(candidatePartsToSend.size()));
            out.writeObject(partNameToSend);
            out.flush();
            sendFile(out, partNameToSend);
            // TODO: send the contents of this part - TEST IT
        } catch (IOException e) {
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
}
