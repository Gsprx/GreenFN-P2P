import misc.Function;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public class TestMain {
    public static void main(String[] args) {
        try {
            ArrayList<String> fileNames = new ArrayList<>();
            fileNames.add("file1.txt");
            fileNames.add("file3.txt");
            HashSet<String> partitionF1 = new HashSet<>();
            partitionF1.add("file1-1.txt");
            partitionF1.add("file1-3.txt");
            partitionF1.add("file1-4.txt");
            HashSet<String> partitionF2 = new HashSet<>();
            partitionF2.add("file2-1.txt");
            partitionF2.add("file2-2.txt");
            ArrayList<HashSet<String>> partitionFiles = new ArrayList<>();
            partitionFiles.add(partitionF1);
            partitionFiles.add(partitionF2);
            ArrayList<String> seederOfFiles = new ArrayList<>();
            seederOfFiles.add("file2.txt");

            ServerSocket server = new ServerSocket(5555);
            Socket s1 = new Socket("localhost", 5555);
            ObjectOutputStream s1_out = new ObjectOutputStream(s1.getOutputStream());
            s1_out.writeInt(Function.SEEDER_SERVER.getEncoded());
            s1_out.flush();
            Socket connection1 = server.accept();
            Thread t1 = new PeerServerThread(connection1, fileNames, partitionFiles, seederOfFiles, "");
            t1.start();
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Socket s2 = new Socket("localhost", 5555);
            ObjectOutputStream s2_out = new ObjectOutputStream(s2.getOutputStream());
            s2_out.writeInt(Function.SEEDER_SERVER.getEncoded());
            s2_out.flush();
            Socket connection2 = server.accept();
            Thread t2 = new PeerServerThread(connection2, fileNames, partitionFiles, seederOfFiles, "");
            t2.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
