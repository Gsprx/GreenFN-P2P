import javax.sound.midi.Track;
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
    private ConcurrentHashMap<String, HashSet<Integer>> sharedDirectory;


    public TrackerThread(Socket connection, ConcurrentHashMap<String, String> registeredUsers, ConcurrentHashMap<Integer,String[]> activeUsers,
                         ConcurrentHashMap<String, int[]> userCountStatistics, ConcurrentHashMap<String, HashSet<Integer>> sharedDirectory) {
        this.connection = connection;
        this.registeredUsers = registeredUsers;
        this.activeUsers = activeUsers;
        this.userCountStatistics = userCountStatistics;
        this.sharedDirectory = sharedDirectory;
    }



    /*
        Tracker thread will serve the peer it's connected to until the peer decides to log out.
     */
    @Override
    public void run() {
        try {
            super.run();
            in = new ObjectInputStream(connection.getInputStream());

            outer:
            while (true) {
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
                        break outer; //terminate the thread
                    }
                    case 4: {
                        peerInform();
                    }

                }//switch

            }//while

        }//try
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


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
            if(registeredUsers.containsKey(username)){
                //user already exists
                //register failed
                out.writeInt(0);
                out.flush();
                Tracker.printMessage("User " + username + " attempted to register and failed!");
                return;
            }
            //user does not exist
            String password = (String) in.readObject();
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
                out.writeInt(1);
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

            //Use the merge function to add the token id (if it is not there already) to the set of owners of the specific file, if such
            //registry does not exist, it creates the set of owners starting with this token id as the only one.
            for (String file : files){
                sharedDirectory.merge(file, new HashSet<>(){{add(tokenID);}}, (oldList, newList) -> {
                    oldList.addAll(newList);
                    return oldList;
                });
                Tracker.printMessage("User with token ID: " + tokenID + " added a file to the shared directory - " + file);
            }

            //obtain network information (IP, port)
            String peerIP = (String) in.readObject();
            String peerPort = (String) in.readObject();

            //add to unfinished user details the complete information
            String[] userDetails = activeUsers.get(tokenID);
            userDetails[0] = peerIP; userDetails[1] = peerPort;
            Tracker.printMessage("User's details obtained - " + activeUsers.get(tokenID).toString() + " ID: " + tokenID);

        }catch (IOException | ClassNotFoundException e){
            throw new RuntimeException(e);
        }
    }



}
