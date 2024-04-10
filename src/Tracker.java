import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class Tracker extends Thread{

    //Registered Users consists of (Username,Password) tuples
    private ConcurrentHashMap<String, String> registeredUsers;

    //Active users consists of (tokenID, [IP, port, username]) tuples
    private ConcurrentHashMap<Integer,String[]> activeUsers;

    //User count statistics consists of (Username, [countDownload,countFail]) tuples
    private ConcurrentHashMap<String, int[]> userCountStatistics;


    public Tracker(){
        registeredUsers = new ConcurrentHashMap<>();
        activeUsers = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) {
        Tracker tracker = new Tracker();
        tracker.start();
    }

    public void run(){

        try {
            ServerSocket server = new ServerSocket(5000);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new TrackerThread(inConnection,registeredUsers,activeUsers,userCountStatistics);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
