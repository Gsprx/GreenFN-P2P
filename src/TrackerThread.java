import misc.Function;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class TrackerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;
    private ConcurrentHashMap<String, String> registeredUsers;
    private ConcurrentHashMap<Integer,String[]> activeUsers;
    private ConcurrentHashMap<String, int[]> userCountStatistics;
    private ConcurrentHashMap<String, HashSet<Integer>> availablePartitions;
    private HashSet<String> allFiles;
    private ConcurrentHashMap<String, HashSet<String>> allFilePartitions;
    private ConcurrentHashMap<String, HashSet<Integer>> fileSeeders;

    public TrackerThread(Socket connection, ConcurrentHashMap<String, String> registeredUsers, ConcurrentHashMap<Integer,String[]> activeUsers,
                         ConcurrentHashMap<String, int[]> userCountStatistics, ConcurrentHashMap<String, HashSet<Integer>> availablePartitions, HashSet<String> allFiles,
                         ConcurrentHashMap<String, HashSet<String>> allFilePartitions, ConcurrentHashMap<String, HashSet<Integer>> fileSeeders) {
        this.connection = connection;
        this.registeredUsers = registeredUsers;
        this.activeUsers = activeUsers;
        this.userCountStatistics = userCountStatistics;
        this.availablePartitions = availablePartitions;
        this.allFiles = allFiles;
        this.allFilePartitions = allFilePartitions;
        this.fileSeeders = fileSeeders;

        try {
            in = new ObjectInputStream(connection.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
        Tracker thread will serve the peer it's connected to until the peer decides to log out.
     */
    @Override
    public void run() {
        try {
            //decide what to do based on the code sent
            int code = in.readInt();

            switch (code) {
                case 1: {
                    registerUser();
                    break;
                }
                case 2: {
                    loginUser();
                    break;
                }
                case 3: {
                    logoutUser();
                    break;
                }
                case 4: {
                    peerInform();
                    break;
                }
                case 5: {
                    peerNotify();
                    break;
                }
                case 6:{
                    replyList();
                    break;
                }
                case 7:{
                    replyDetails();
                    break;
                }
                case 11:{
                    seederInform();
                    break;
                }
                case 13:
                    replyUserStatistics();
                    break;
            }//switch
        }//try
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
    ======================================

              HELPER FUNCTIONS

    ======================================
     */


    private int getSessionID() {
        Random rand = new Random();
        int sessionID = rand.nextInt(1,10000); //create a pseudorandom id to use for the session

        // putIfAbsent will return null if there is no key mapping for the key given, else it returns the previous value which is non-null.
        // the loop will stop when the key is successfully added to the concurrent hashmap (the return of the method will be null)
        while(activeUsers.containsKey(sessionID)){
            sessionID = rand.nextInt(1,10000);
        }
        return sessionID;
    }

    private boolean checkActive(int tokenID){
        //check if token exists in active users
        if(!activeUsers.containsKey(tokenID)){
            //token does not exist, peer cannot be active
            return false;
        }
        //token exists check active status
        String[] info = activeUsers.get(tokenID);

        String ip = info[0];
        int port = Integer.parseInt(info[1]);

        try{
            Socket socket = new Socket(ip,port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());

            //write checkAlive code
            out.writeInt(Function.CHECK_ACTIVE.getEncoded());
            out.flush();

            //await response of 1 for OK
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            int reply = (int) in.readObject();
            Tracker.printMessage("Peer with ip:" + ip + " was confirmed to be active.");
            socket.close();
            return true;
        } catch (IOException | ClassNotFoundException e) {
            return false;
        }
    }

    //green fn
    //used to remove a tokenID from the system
    private void removeTokenID(int tokenID){

        //remove the token from active users log
        activeUsers.remove(tokenID);

        //remove the token from all file partitions it exists as an owner in
        for(HashSet<Integer> filePartOwners : availablePartitions.values()){
            synchronized (filePartOwners){
                filePartOwners.remove(tokenID);
            }
        }
        //remove the token as a seeder from any file it exists in
        for(HashSet<Integer> seeders : fileSeeders.values()){
            synchronized (fileSeeders){
                seeders.remove(tokenID);
            }
        }
        Tracker.printMessage("Token: " + tokenID + " was removed from the system!");
    }



    /*
    ======================================

            CODE BASED FUNCTIONS

    ======================================
     */


    //code = 1
    //expected input is String username, String password
    private void registerUser(){
        try {
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            String username = (String) in.readObject();
            String password = (String) in.readObject();
            if(registeredUsers.containsKey(username)){
                //user already exists
                //register failed
                out.writeInt(0);
                out.flush();
                Tracker.printMessage("User " + username + " attempted to register and failed!");
                return;
            }
            //user does not exist
            registeredUsers.putIfAbsent(username, password);
            userCountStatistics.putIfAbsent(username, new int[2]);
            //register successful
            Tracker.printMessage("User " + username + " successfully registered!");
            out.writeInt(1);
            out.flush();


        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    //code = 2
    //expected input is String username, String password
    private void loginUser(){
        try{
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            String username = (String) in.readObject();
            String password = (String) in.readObject();

            if(registeredUsers.containsKey(username) && registeredUsers.get(username).equals(password)){
                //login successful
                int tokenID = getSessionID();
                
                //add peer to active peers
                String[] initialDetails = new String[3]; initialDetails[2] = username;
                activeUsers.put(tokenID, initialDetails);

                //send session id back to peer.
                Tracker.printMessage("User " + username + " logged into the system successfully, using ID: " + tokenID);
                out.writeInt(tokenID);
                out.flush();
                return;
            }
            //login not successful
            Tracker.printMessage("User's " + username + " attempted login failed!");
            out.writeInt(0);
            out.flush();


        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    //code = 3
    //expected input is int token id
    private void logoutUser() {
        try {
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            int tokenID = in.readInt();

            //remove user from active users
            removeTokenID(tokenID);
            Tracker.printMessage("User with ID: " + tokenID + " logged out successfully!");

            out.writeInt(1);
            out.flush();


            //close all things related to the socket
            in.close();
            connection.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //code = 4
    //expected input is int token id, ArrayList<String> filenames, ArrayList<HashSet<String>> partitions and communication details
    private void peerInform(){
        try{
            //check if token id is valid
            int tokenID = in.readInt();
            if(!activeUsers.containsKey(tokenID)){
                //invalid token, reject call and return
                Tracker.printMessage("User with token ID: " + tokenID + " attempted to inform but token was invalid!");
                return;
            }


            //obtain files and add them to trackers data
            ArrayList<String> files = (ArrayList<String>) in.readObject();

            //add new files to allFiles if they do not already exist
            synchronized (allFiles){
                for(String file : files){
                    if (allFiles.add(file)){
                        Tracker.printMessage("User with token ID: " + tokenID + " added a file to all files - " + file);
                    }
                }
            }


            //obtain files' partitions and add them to tracker data for each file
            ArrayList<HashSet<String>> filesPartitions = (ArrayList<HashSet<String>>) in.readObject();

            synchronized (allFilePartitions) {
                for (int i = 0; i< files.size(); i++){
                    String fileName = files.get(i);
                    HashSet<String> filePartitions = filesPartitions.get(i);
                    if(!allFilePartitions.containsKey(fileName)){
                        allFilePartitions.put(fileName, filePartitions);
                        Tracker.printMessage("User with token ID: " + tokenID + " added a file partition to all files partitions - " + filePartitions);
                    }
                    else{
                        allFilePartitions.get(fileName).addAll(filePartitions);
                        Tracker.printMessage("User with token ID: " + tokenID + " added a file partition to all files partitions - " + filePartitions);
                    }
                }
            }

            //use the merge function to add the token id (if it is not there already) to the set of owners of the specific file partition, if such
            //registry does not exist, it creates the set of owners starting with this token id as the only one.
            for (HashSet<String> filePartitions : filesPartitions) {
                for (String partition : filePartitions) {
                    availablePartitions.merge(partition, new  HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                        oldList.addAll(newList);
                        return oldList;
                    });
                }
            }


            //obtain network information (IP, port)
            String peerIP = (String) in.readObject();
            String peerPort = (String) in.readObject();

            //add to unfinished user details the complete information
            String[] userDetails = activeUsers.get(tokenID);
            userDetails[0] = peerIP; userDetails[1] = peerPort;

            Tracker.printMessage("User's details obtained - " + Arrays.toString(activeUsers.get(tokenID)) + " ID: " + tokenID);
        }catch (IOException | ClassNotFoundException e){
            throw new RuntimeException(e);
        }
    }


    //code = 5
    //expected input is int 0 - fail, 1 - success
    private void peerNotify(){
        try {
            int result = in.readInt();
            // read 1 if notify is about a successful action
            if (result == 1){
                peerNotifySuccess();
            }
            // read 0 if notify is about a failed action
            else{
                peerNotifyFail();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //5 -> code = 1
    //expected input is int tokenID. String partition, String senderUsername
    private void peerNotifySuccess(){
        try {
            //identify recipient
            int tokenID = in.readInt();
            //identify file received
            String filePartition = (String) in.readObject();
            //identify username of sender
            String senderUsername = (String) in.readObject();

            //add recipient to list of owners of the file using the merge function
            availablePartitions.merge(filePartition, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                oldList.addAll(newList);
                return oldList;
            });

            //increase count download for sender
            userCountStatistics.get(senderUsername)[0]++;
            Tracker.printMessage("User with token : " + tokenID + " notified the tracker of a successful download from user: " + senderUsername + " for the file partition: " + filePartition);

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    //5 -> code = 0
    //expected input is int token id, String senderUsername
    private void  peerNotifyFail(){
        try{
            //identify token of recipient
            int tokenID = in.readInt();
            //identify username of sender
            String senderUsername = (String) in.readObject();

            //increase count fail for sender
            userCountStatistics.get(senderUsername)[1]++;
            Tracker.printMessage("User with token: " + tokenID + " notified the tracker of a failed download from user: " + senderUsername);
        }
        catch (IOException | ClassNotFoundException e){
            throw new RuntimeException(e);
        }
    }

    //code = 6
    //expected input is int token id
    private void replyList(){
        try{
            //identify requester
            int tokenID = in.readInt();

            ArrayList<String> files = new ArrayList<>();

            for(String file : allFiles){
                files.add(file);
            }

            //send files to requester
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            out.writeObject(files);
            out.flush();
            Tracker.printMessage("User with token: " + tokenID + " received a copy of all the file names available in the system");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //code = 7
    //expected input is int token id, String filename
    private void replyDetails(){
        try {
            //identify requester
            int requesterTokenID = in.readInt();
            //get file name
            String filename = (String) in.readObject();

            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());

            //requested file does not exist in the system
            if(!allFiles.contains(filename)){
                //send code for fail (file does not exist)
                out.writeInt(0);
                out.flush();
                Tracker.printMessage("User with token: " + requesterTokenID + " requested a file that does not exist in the system - " + filename);
                return;
            }

            //prepare reply lists

            //List with [IP,Port,Username]
            ArrayList<String[]> fileOwnersInfo = new ArrayList<>();

            //List with [CountDownload, CountFail]
            ArrayList<int[]> fileOwnersStatistics = new ArrayList<>();

            //List with [{filePartitions}]
            ArrayList<ArrayList<String>> fileOwnersPartitions = new ArrayList<>();

            //List with [SeederBoolean/Bit]
            ArrayList<Boolean> fileOwnersSeederBit = new ArrayList<>();

            //collect all token ids from file's partition owners
            HashSet<Integer> fileOwnerIDs = new HashSet<>();

            //scan all partitions recorded, only add owners on availablePartitions list
            for(String partition : allFilePartitions.get(filename)){
                fileOwnerIDs.addAll(availablePartitions.get(partition));
            }

            //fill reply lists
            for(int token: fileOwnerIDs){
                if(checkActive(token)){
                    //get specific active owner's info
                    String[] activeOwnerInfo = activeUsers.get(token);
                    String ownerUsername = activeOwnerInfo[2];

                    //add owner's info and statistics to reply list
                    fileOwnersInfo.add(activeOwnerInfo);
                    fileOwnersStatistics.add(userCountStatistics.get(ownerUsername));

                    //get owner's partitions for this file
                    ArrayList<String> partitions = new ArrayList<>();
                    for(String partition : allFilePartitions.get(filename)){
                        if(availablePartitions.get(partition).contains(token)){
                            partitions.add(partition);
                        }
                    }
                    //add owner's file partitions
                    fileOwnersPartitions.add(partitions);
                    //add owner's seeder status
                    fileOwnersSeederBit.add(fileSeeders.get(filename).contains(token));
                }
                else{
                    removeTokenID(token);
                }
            }

            //send result to requester
            if(fileOwnersInfo.isEmpty() || fileOwnersStatistics.isEmpty()){
                //send code for fail (no active owners found of requested file)
                out.writeInt(-1);
                out.flush();
            }
            else{
                //send code for success
                out.writeInt(1);
                //send file owners info
                out.writeObject(fileOwnersInfo);
                //send file owners statistics
                out.writeObject(fileOwnersStatistics);
                //send file owners partitions
                out.writeObject(fileOwnersPartitions);
                //send file owners seeder status
                out.writeObject(fileOwnersSeederBit);
                out.flush();
            }

        } catch (IOException | ClassNotFoundException e) {
            Tracker.printMessage("Error! " + e.getMessage());
        }

    }

    // code 13
    private void replyUserStatistics() {
        try {
            //identify requester
            int requesterTokenID = in.readInt();
            // get peer username
            String userName = activeUsers.get(requesterTokenID)[0];
            // get user statistics
            int[] stats = userCountStatistics.get(userName);

            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            out.writeObject(stats);
            out.flush();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //code = 11
    //expected input is token id, String filename and HashSet<String> fileParts
    private void seederInform(){
        try {
            //identify seeder
            int tokenID = in.readInt();

            if(!activeUsers.containsKey(tokenID)){
                //invalid token, reject call and return
                Tracker.printMessage("User with token ID: " + tokenID + " attempted to seeder-inform but token was invalid!");
                return;
            }

            //obtain file name and file partitions from seeder
            String filename = (String) in.readObject();
            HashSet<String> filePartitions = (HashSet<String>) in.readObject();

            //update tracker's relevant data structures

            //add token id to this file's seeders list
            fileSeeders.merge(filename, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                oldList.addAll(newList);
                return oldList;
            });

            //add file partitions to this file's partition list
            allFilePartitions.merge(filename, new HashSet<>(){{addAll(filePartitions);}}, (oldList, newList) -> {
                oldList.addAll(newList);
                return oldList;
            });

            //add token id to available file partition owners
            for (String filePartition : filePartitions){
                availablePartitions.merge(filePartition, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                    oldList.addAll(newList);
                    return oldList;
                });
            }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
