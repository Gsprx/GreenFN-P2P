import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Tracker extends Thread{

    //Registered Users consists of (Username,Password)
    private ConcurrentHashMap<String, String> registeredUsers;

    //Active users consists of (tokenID, [IP, port, username])
    private ConcurrentHashMap<Integer,String[]> activeUsers;

    //User count statistics consists of (Username, [countDownload,countFail])
    private ConcurrentHashMap<String, int[]> userCountStatistics;

    //shared folder catalog, consists of (FileName, list of tokens who own that file)
    private ConcurrentHashMap<String, HashSet<Integer>> sharedDirectory;


    public Tracker(){
        registeredUsers = new ConcurrentHashMap<>();
        activeUsers = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) {
        Tracker tracker = new Tracker();
        tracker.start();
    }

    public static void printMessage(String message){
        System.out.println("[Tracker]> " + message);
    }


    /*
        The main Tracker accepts connections and creates threads to handle each connection until the peer decides to log out of the system.
     */
    public void run(){
        try {
            ServerSocket server = new ServerSocket(5000);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new TrackerThread(inConnection,registeredUsers,activeUsers,userCountStatistics, sharedDirectory);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
