import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class TrackerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;
    private ConcurrentHashMap<String, String> registeredUsers;
    private ConcurrentHashMap<Integer,String[]> activeUsers;
    private ConcurrentHashMap<String, int[]> userCountStatistics;
    private ConcurrentHashMap<String, HashSet<Integer>> allowedFiles;
    private HashSet<String> allFiles;

    public TrackerThread(Socket connection, ConcurrentHashMap<String, String> registeredUsers, ConcurrentHashMap<Integer,String[]> activeUsers,
                         ConcurrentHashMap<String, int[]> userCountStatistics, ConcurrentHashMap<String, HashSet<Integer>> allowedFiles, HashSet<String> allFiles) {
        this.connection = connection;
        this.registeredUsers = registeredUsers;
        this.activeUsers = activeUsers;
        this.userCountStatistics = userCountStatistics;
        this.allowedFiles = allowedFiles;
        this.allFiles = allFiles;

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
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            //write checkAlive code
            out.writeInt(10);
            out.flush();

            //await response of 1 for OK
            int reply = in.readInt();

            return reply == 1;
        } catch (IOException e) {
            return false;
        }
    }

    //used to remove a tokenID from the system
    private void removeTokenID(int tokenID){

        //remove the token from active users log
        activeUsers.remove(tokenID);

        //remove the token from all files it exists as an owner in
        for(HashSet<Integer> fileOwners : allowedFiles.values()){
            fileOwners.remove(tokenID);
        }
        Tracker.printMessage("Token: " + tokenID + " was removed from the system due to inactivity!");
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
    private void logoutUser(){
        try {
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            int tokenID = in.readInt();
            String[] loggedOutUserDetails = activeUsers.remove(tokenID);
            if (loggedOutUserDetails != null){
                //removed from active users
                Tracker.printMessage("User " + Arrays.toString(loggedOutUserDetails) + " with ID " + tokenID + " logged out successfully!");
                out.write(1);
                out.flush();
            }
            else {
                //token id does not exist (for some green fn reason)
                Tracker.printMessage("User with ID " + tokenID + " failed to log out!");
                out.write(0);
                out.flush();
            }

            //close all things related to the socket
            in.close();
            connection.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //code = 4
    //expected input is int token id, ArrayList<String> filenames and communication details
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

            //Add new files to allFiles if they do not already exist
            synchronized (allFiles){
                for(String file : files){
                    if (allFiles.add(file)){
                        Tracker.printMessage("User with token ID: " + tokenID + " added a file to all files - " + file);
                    }
                }
            }


            //Use the merge function to add the token id (if it is not there already) to the set of owners of the specific file, if such
            //registry does not exist, it creates the set of owners starting with this token id as the only one.
            for (String file : files){
                allowedFiles.merge(file, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                    oldList.addAll(newList);
                    return oldList;
                });
                Tracker.printMessage("User with token ID: " + tokenID + " added a file to the allowed files - " + file);
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
    //expected input is int tokenID. String filename, String senderUsername
    private void peerNotifySuccess(){
        try {
            //identify recipient
            int tokenID = in.readInt();
            //identify file received
            String filename = (String) in.readObject();
            //identify username of sender
            String senderUsername = (String) in.readObject();

            //add recipient to list of owners of the file
            allowedFiles.merge(filename, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                oldList.addAll(newList);
                return oldList;
            });

            //increase count download for sender
            userCountStatistics.get(senderUsername)[0]++;
            Tracker.printMessage("User with token : " + tokenID + " notified the tracker of a successful download from user: " + senderUsername + " for the file: " + filename);

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
            int tokenID = in.readInt();
            //get file name
            String filename = (String) in.readObject();

            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());

            //requested file does not exist in the system
            if(!allowedFiles.containsKey(filename)){
                //send code for fail (file does not exist)
                out.writeInt(0);
                out.flush();
                Tracker.printMessage("User with token: " + tokenID + " requested a file that does not exist - " + filename);
                return;
            }

            //prepare reply lists

            //List with [IP,Port,Username]
            ArrayList<String[]> fileOwnersInfo = new ArrayList<>();
            //List with [CountDownload, CountFail]
            ArrayList<int[]> fileOwnersStatistics = new ArrayList<>();

            //check all owners of requested file
            HashSet<Integer> fileOwnerIDs = allowedFiles.get(filename);
            for(int ownerID : fileOwnerIDs){
                if(checkActive(ownerID)){
                    //get specific active owners info
                    String[] activeOwnerInfo = activeUsers.get(ownerID);
                    String ownerUsername = activeOwnerInfo[2];

                    //add owners info and statistics to reply list
                    fileOwnersInfo.add(activeOwnerInfo);
                    fileOwnersStatistics.add(userCountStatistics.get(ownerUsername));
                }
                //remove inactive tokens from the system
                else {
                    removeTokenID(ownerID);
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
                out.flush();

                //send file owners info
                out.writeObject(fileOwnersInfo);
                out.flush();
                //send file owners statistics
                out.writeObject(fileOwnersStatistics);
                out.flush();
            }

        } catch (IOException | ClassNotFoundException e) {
            Tracker.printMessage("Error! " + e.getMessage());
        }

    }

}
