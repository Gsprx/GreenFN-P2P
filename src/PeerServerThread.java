import misc.Config;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    public PeerServerThread(Socket connection, ArrayList<String> filesInNetwork, ArrayList<HashSet<String>> partitionsInNetwork,
                            ArrayList<String> seederOfFiles, String shared_directory, HashMap<String, String> threadByFile,
                            HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread,
                            ReentrantLock lock, ConcurrentHashMap<String, ArrayList<String>> partitionsByPeer) {
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
            if(!filesInNetwork.contains(filename)){
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
    private void collaborativeDownloadHandler(){
        try {
            String fileName = (String) in.readObject();
            // get the parts of the files that the user already has (so the other peers don't send already existing files)
            HashMap<String, ArrayList<String>> partitionsOwnedByPeer = (HashMap<String, ArrayList<String>>) in.readObject();
            if(this.seederOfFiles.contains(fileName)) {
                seederServe(fileName, partitionsOwnedByPeer);
            }else {
                collaborativeDownload(fileName, partitionsOwnedByPeer);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Option | Send file to peer.
     */
    private void sendFile(ObjectOutputStream out, String filename){
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

            // if the file name does not exit in the struct, add it and wait for 200ms for more potential requests for this file
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
                this.peerPartitionsByThread.put(threadName, innerMap);
                //Testing-end

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                lock.lock();
                this.threadByFile.remove(fileName);
                //Testing
                HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                //Testing-end
                lock.unlock();
                //Random initialize
                Random rand = new Random();
                //Testing
                //Convert involvedPeers to a list
                List<Socket> involvedPeers = new ArrayList<>(partitionsRequestsPerPeer.keySet());
                //Select a random peer
                Socket selectedPeer = involvedPeers.get(rand.nextInt(involvedPeers.size()));
                //Retrieve the ArrayList of file partitions requested of the random peer
                ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(selectedPeer);
                //Select a random partition from the ArrayList
                String selectedPart = requestedPartitions.get(rand.nextInt(requestedPartitions.size()));
                //Get outputStream
                ObjectOutputStream outputStream = new ObjectOutputStream(selectedPeer.getOutputStream());
                //The codes can be changed to fit the method in the Peer!!!!!!!!!!!!!!!!!!!
                //Send "OK" code - this can be removed
                outputStream.writeInt(1);
                outputStream.flush();
                sendFile(outputStream,selectedPart);
                //Send "DENIED" to the rest
                involvedPeers.remove(selectedPeer);
                for(Socket socket : involvedPeers){
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    outputStream.writeInt(0);
                    outputStream.flush();
                }
                //Testing-end

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
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /***/
    private void collaborativeDownload(String fileName, HashMap<String, ArrayList<String>> partitionsReqByPeer) {
        try {
            // get the specific thread name
            String threadName = Thread.currentThread().getName();

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

                if(partitionsRequestsPerPeer.size() == 1) {
                    //A
                    for (Socket socket : partitionsRequestsPerPeer.keySet()) {
                        ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
                        //Retrieve the ArrayList of file partitions requested of the random peer
                        ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(socket);
                        //Select a random partition from the ArrayList
                        String selectedPart = requestedPartitions.get(new Random().nextInt(requestedPartitions.size()));
                        if (!this.filesInNetwork.contains(selectedPart)) {//todo with partitionsInNetwork
                            //Send "OK" code - this can be removed
                            outputStream.writeObject("OK");
                            outputStream.flush();
                        } else {
                            //Send "OK" code - this can be removed
                            outputStream.writeObject("OK");
                            outputStream.flush();
                            //Send the selected part
                            sendFile(outputStream,selectedPart);
                        }
                    }
                } else {
                    //B
                    int[] chanceBucket = {0, 0, 1, 1, 1, 1, 2, 2, 2, 2};
                    int randomIndex = new Random().nextInt(10);
                    int decision = chanceBucket[randomIndex];

                    switch (decision) {
                        case 0:
                            break;
                        case 1:
                            break;
                        case 2:
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
