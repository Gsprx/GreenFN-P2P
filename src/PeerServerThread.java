import misc.Config;

import java.io.*;
import java.net.Socket;
import java.util.*;
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
    // Map of partitions each peer requested and the thread that works on serving them, (#12ae23, [peer1, {file1-1.txt, file1-3.txt}])
    private HashMap<String, HashMap<String, ArrayList<String>>> peerPartitionsByThread;
    private HashMap<String, HashMap<Socket, ArrayList<String>>> peerPartitionsByThread2;
    // Locks the threadByFile and peerPartitionsByThread
    private ReentrantLock lock;

    public PeerServerThread(Socket connection, ArrayList<String> filesInNetwork, ArrayList<HashSet<String>> partitionsInNetwork,
                            ArrayList<String> seederOfFiles, String shared_directory, HashMap<String, String> threadByFile,
                            HashMap<String, HashMap<String, ArrayList<String>>> peerPartitionsByThread, ReentrantLock lock) {
        //handle connection
        this.filesInNetwork = filesInNetwork;
        this.partitionsInNetwork = partitionsInNetwork;
        this.seederOfFiles = seederOfFiles;
        this.connection = connection;
        this.shared_directory = shared_directory;
        this.threadByFile = threadByFile;
        this.peerPartitionsByThread = peerPartitionsByThread;
        this.lock = lock;
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
            HashMap<String, ArrayList<String>> partitionsReqByPeer = (HashMap<String, ArrayList<String>>) in.readObject();
            if(this.seederOfFiles.contains(fileName)){
                seederServe(fileName, partitionsReqByPeer);
            }else {
                collaborativeDownload(fileName, partitionsReqByPeer);
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

    private void seederServe(String fileName, HashMap<String, ArrayList<String>> partitionsReqByPeer) {
        try {
            // get the specific thread name
            String threadName = Thread.currentThread().getName();
            // get file name
//            String fileName = (String) in.readObject();
            // get peer and the partitions he requested for this file name
//            HashMap<String, ArrayList<String>> partitionsReqByPeer = (HashMap<String, ArrayList<String>>) in.readObject();
            // if the file name does not exit in the struct, add it and wait for 200ms for more potential requests for this file
            lock.lock();
            if (!this.threadByFile.containsKey(fileName)) {
                this.threadByFile.put(fileName, threadName);
                this.peerPartitionsByThread.put(threadName, partitionsReqByPeer);

                //Testing
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                this.peerPartitionsByThread2.put(threadName, innerMap);
                //Testing-end

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                lock.lock();
                this.threadByFile.remove(fileName);
                HashMap<String, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                //Testing
                HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer2 = this.peerPartitionsByThread2.remove(threadName);
                //Testing-end
                lock.unlock();
                //Random initialize
                Random rand = new Random();
                //Convert involvedPeers to a list
                List<String> involvedPeers = new ArrayList<>(partitionsRequestsPerPeer.keySet());
                //Select a random peer
                String selectedPeer = involvedPeers.get(rand.nextInt(involvedPeers.size()));
                //Retrieve the ArrayList of file partitions requested of the random peer
                ArrayList<String> requestedPartitions = partitionsRequestsPerPeer.get(selectedPeer);
                //Select a random partition from the ArrayList
                String selectedPartition = requestedPartitions.get(rand.nextInt(requestedPartitions.size()));

                //Testing
                //Convert involvedPeers to a list
                List<Socket> involvedPeers2 = new ArrayList<>(partitionsRequestsPerPeer2.keySet());
                //Select a random peer
                Socket selectedPeer2 = involvedPeers2.get(rand.nextInt(involvedPeers.size()));
                //Retrieve the ArrayList of file partitions requested of the random peer
                ArrayList<String> requestedPartitions2 = partitionsRequestsPerPeer2.get(selectedPeer2);
                //Select a random partition from the ArrayList
                String selectedPart2 = requestedPartitions2.get(rand.nextInt(requestedPartitions2.size()));
                //Get outputStream
                ObjectOutputStream outputStream = new ObjectOutputStream(selectedPeer2.getOutputStream());
                //The codes can be changed to fit the method in the Peer!!!!!!!!!!!!!!!!!!!
                //Send "OK" code - this can be removed
                outputStream.writeObject("OK");
                outputStream.flush();
                //Send the selected part
//                outputStream.writeObject(selectedPart2);
//                outputStream.flush();
                sendFile(outputStream,selectedPart2);
                //Send "DENIED" to the rest
                involvedPeers2.remove(selectedPeer2);
                for(Socket socket : involvedPeers2){
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    outputStream.writeObject("DENIED");
                    outputStream.flush();
                }
                //Testing-end

            } else {
                String initThread = this.threadByFile.get(fileName);
                HashMap<String, ArrayList<String>> newPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);

                //Testing
                HashMap<Socket, ArrayList<String>> newPeerPartitionsReq2 = this.peerPartitionsByThread2.get(initThread);
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                newPeerPartitionsReq2.putAll(innerMap);
                this.peerPartitionsByThread2.put(initThread,newPeerPartitionsReq2);
                //Testing-end

                newPeerPartitionsReq.putAll(partitionsReqByPeer);
                this.peerPartitionsByThread.put(initThread, newPeerPartitionsReq);
                lock.unlock();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /***/
    private void collaborativeDownload(String fileName, HashMap<String, ArrayList<String>> partitionsReqByPeer){
        try {
            // get the specific thread name
            String threadName = Thread.currentThread().getName();

            lock.lock();
            if (!this.threadByFile.containsKey(fileName)) {
                this.threadByFile.put(fileName, threadName);
                this.peerPartitionsByThread.put(threadName, partitionsReqByPeer);

                //Testing
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                peerPartitionsByThread2.put(threadName, innerMap);
                //Testing-end

                lock.unlock();
                TimeUnit.MILLISECONDS.sleep(200);
                lock.lock();
                this.threadByFile.remove(fileName);
                HashMap<String, ArrayList<String>> partitionsRequestsPerPeer = this.peerPartitionsByThread.remove(threadName);
                //Testing
                HashMap<Socket, ArrayList<String>> partitionsRequestsPerPeer2 = this.peerPartitionsByThread2.remove(threadName);
                //Testing-end
                lock.unlock();

                if(partitionsRequestsPerPeer2.size()==1){
                    //A
                    for (Socket socket : partitionsRequestsPerPeer2.keySet()){
                        ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
                        //Retrieve the ArrayList of file partitions requested of the random peer
                        ArrayList<String> requestedPartitions = partitionsRequestsPerPeer2.get(socket);
                        //Select a random partition from the ArrayList
                        String selectedPart = requestedPartitions.get(new Random().nextInt(requestedPartitions.size()));
                        //Send "OK" code - this can be removed
                        outputStream.writeObject("OK");
                        outputStream.flush();
                        //Send the selected part
                        sendFile(outputStream,selectedPart);
                    }
                }else {
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
                HashMap<String, ArrayList<String>> newPeerPartitionsReq = this.peerPartitionsByThread.get(initThread);

                //Testing
                HashMap<Socket, ArrayList<String>> newPeerPartitionsReq2 = this.peerPartitionsByThread2.get(initThread);
                HashMap<Socket, ArrayList<String>> innerMap = new HashMap<>();
                for (String key : partitionsReqByPeer.keySet()) {
                    innerMap.put(this.connection, partitionsReqByPeer.get(key));
                }
                newPeerPartitionsReq2.putAll(innerMap);
                this.peerPartitionsByThread2.put(initThread,newPeerPartitionsReq2);
                //Testing-end

                newPeerPartitionsReq.putAll(partitionsReqByPeer);
                this.peerPartitionsByThread.put(initThread, newPeerPartitionsReq);
                lock.unlock();
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
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
