import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class Tracker extends Thread{

    //Registered Users consists of (Username,Password)
    private static ConcurrentHashMap<String, String> registeredUsers;

    //Active users consists of (tokenID, [IP, port, username])
    private ConcurrentHashMap<Integer,String[]> activeUsers;

    //User count statistics consists of (Username, [countDownload,countFail])
    private ConcurrentHashMap<String, int[]> userCountStatistics;

    //Allowed files contains only the files in fileDownloadList.txt and the tokens of their owners
    private ConcurrentHashMap<String, HashSet<Integer>> allowedFiles;

    //Hashset will all available files
    private HashSet<String> allFiles;


    public Tracker(){
        registeredUsers = new ConcurrentHashMap<>();
        activeUsers = new ConcurrentHashMap<>();
        userCountStatistics = new ConcurrentHashMap<>();
        allowedFiles = new ConcurrentHashMap<>();
        allFiles = new HashSet<>();
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
                Thread t = new TrackerThread(inConnection,registeredUsers,activeUsers,userCountStatistics, allowedFiles, allFiles);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
