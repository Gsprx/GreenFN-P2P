import misc.Config;

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


//                          Unused as of part 2 of project.
//    Allowed files contains only the files in fileDownloadList.txt and the tokens of their owners
//    private ConcurrentHashMap<String, HashSet<Integer>> availableFiles;

    //Hashset with all recorded files
    private HashSet<String> allFiles;

    //Map of all partitions recorded for each file (FileName, {partNum1,partNum2, ...})
    private ConcurrentHashMap<String, HashSet<String>> allFilePartitions;

    //Map of all owners of each file partition (filePart, {ownerID1 ,ownerID2 ...})
    private ConcurrentHashMap<String, HashSet<Integer>> availablePartitions;

    //Map of all seeders for each file in the system
    private ConcurrentHashMap<String,HashSet<Integer>> fileSeeders;

    public Tracker(){
        registeredUsers = new ConcurrentHashMap<>();
        activeUsers = new ConcurrentHashMap<>();
        userCountStatistics = new ConcurrentHashMap<>();
        availablePartitions = new ConcurrentHashMap<>();
        allFiles = new HashSet<>();
        allFilePartitions = new ConcurrentHashMap<>();
        fileSeeders = new ConcurrentHashMap<>();
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
            ServerSocket server = new ServerSocket(Config.TRACKER_PORT);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new TrackerThread(inConnection,registeredUsers,activeUsers,userCountStatistics, availablePartitions, allFiles, allFilePartitions, fileSeeders);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
