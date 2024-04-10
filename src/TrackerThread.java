import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class TrackerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;
    private ConcurrentHashMap<String, String> registeredUsers;
    private ConcurrentHashMap<Integer,String[]> activeUsers;
    private ConcurrentHashMap<String, int[]> userCountStatistics;


    public TrackerThread(Socket connection, ConcurrentHashMap<String, String> registeredUsers, ConcurrentHashMap<Integer,String[]> activeUsers,
                         ConcurrentHashMap<String, int[]> userCountStatistics) {
        this.connection = connection;
        this.registeredUsers = registeredUsers;
        this.activeUsers = activeUsers;
        this.userCountStatistics = userCountStatistics;
    }



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

                }//switch

            }//while

        }//try
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    /*
    ======================================

            CODE BASED FUNCTIONS

    ======================================
     */


    //code = 1
    //expected input is String, String
    private void registerUser(){
        try {
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            String username = (String) in.readObject();
            if(registeredUsers.containsKey(username)){
                //user already exists
                //register failed
                out.writeInt(0);
                out.flush();
                return;
            }
            //user does not exist
            String password = (String) in.readObject();
            registeredUsers.putIfAbsent(username, password);
            //register successful
            out.writeInt(1);
            out.flush();


        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    //code = 2
    //expected input is String, String
    private void loginUser(){
        try{
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            String username = (String) in.readObject();
            String password = (String) in.readObject();

            if(registeredUsers.containsKey(username) && registeredUsers.get(username).equals(password)){
                //login successful
                Random rand = new Random();
                int sessionID = rand.nextInt(1,10000); //create a pseudorandom id to use for the session

                String[] userDetails = new String[3];
                userDetails[0] = connection.getInetAddress().getHostAddress(); userDetails[1] = String.valueOf(connection.getLocalPort());; userDetails[2] = username;

                // putIfAbsent will return null if there is no key mapping for the key given, else it returns the previous value which is non-null.
                // the loop will stop when the key is successfully added to the concurrent hashmap (the return of the method will be null)
                while(activeUsers.putIfAbsent(sessionID,userDetails)!=null){
                    sessionID = rand.nextInt(1,10000);
                }

                //login successful, send session id back to peer.
                out.writeInt(1);
                out.writeInt(sessionID);
                out.flush();
                return;
            }
            //login not successful
            out.writeInt(0);
            out.flush();


        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    //code = 3
    //expected input is int
    private void logoutUser(){
        try {
            ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
            int tokenID = in.readInt();
            if (activeUsers.remove(tokenID) != null){
                //removed from active users
                out.write(1);
                out.flush();
            }
            else {
                //token id does not exist (for some green fn reason)
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


}
