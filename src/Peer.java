import misc.Config;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class Peer {
    /**
     * Run peer.
     * This method is called when we want to run a peer to show and manage options.
     */
    public void runPeer() {
        boolean running = true;
        // peer actions
        while (running) {
            System.out.println("1) Register\n2) Login\n");
            String option;
            // if the user gives invalid info then ask again
            do {
                System.out.print("> ");
                Scanner inp = new Scanner(System.in);
                option = inp.nextLine().toLowerCase();
                if (option.equals("exit")) {
                    running = false;
                }
                else if (!option.equals("1") && !option.equals("2")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("exit"));

            // if running = false, that means user requested exit
            if (!running) continue;

            // manage options
            // option 1: register peer
            if (option.equals("1")) {
                peerRegister();
                System.out.println("-------------------------------------------\n");
            }
            // option 2: peer login
            else {
                peerLogin();
                System.out.println("-------------------------------------------\n");
            }
        }
    }

    /**
     * Register peer.
     * This method is called if the user chose to register.
     * Creates a new account and sends it to the tracker.
     */
    private void peerRegister() {
        System.out.println("\n|Register User|");

        // account attributes
        String username;
        String password;

        // username
        System.out.print("Enter username: ");
        Scanner inp = new Scanner(System.in);
        username = inp.nextLine();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine();

        // TODO: Send to tracker and wait for response
        // BTW eftiaxa kati poly wraia config kai trackerfunction arxeia gia na mhn grafete ta ports, ta ips kai
        // tis leitourgies tou tracker me to xeri kathe fora
        /*
          NOTE TO NIKOS:
          Το response θα λέει για επιτυχημένο ή αποτυχημένο register.
          Βάζω μια μεταβλητά response απο κάτω για να συνεχίσω το flow της κονσόλας.
          Όταν εσυ θα κανείς τα streams, βάλε αυτή η μεταβλητή να παίρνει την τιμή της απο τον tracker
          (αν απέτυχε 0, αν πέτυχε 1)
         */
        int response = 1;
        if (response == 1) System.out.println("[+] User registered successfully");
        else System.out.println("[-] User already exists");
    }

    /**
     * Peer Login.
     * This method is called if the user chose to log in.
     * Enter details of account and send it to the tracker for validation and verification.
     * After successful logIn tracker responds with token_id.
     * After logging in present more functionalities to the peer.
     */
    private void peerLogin() {
        System.out.println("\n|User Login|");

        // account attributes
        String username;
        String password;

        // username
        System.out.print("Enter username: ");
        Scanner inp = new Scanner(System.in);
        username = inp.nextLine();
        // password
        System.out.print("Enter password: ");
        inp = new Scanner(System.in);
        password = inp.nextLine();

        // TODO: Send to tracker and wait for response
        /*
          NOTE TO NIKOS:
          Το response θα λέει για επιτυχημένο ή αποτυχημένο login.
          Βάζω μια μεταβλητά response απο κάτω για να συνεχίσω το flow της κονσόλας.
          Όταν εσυ θα κανείς τα streams, βάλε αυτή η μεταβλητή να παίρνει την τιμή της απο τον tracker
          (αν απέτυχε 0, αν πέτυχε το token_id) green fn
         */
        int response = 1;
        if (response != 0) runLoggedIn(response);
        else System.out.println("[-] Wrong credentials");
    }

    /**
     * After the peer logged in, he has more options.
     */
    private void runLoggedIn(int token) {
        System.out.println("\n------------------Welcome------------------");
        boolean running = true;
        // logged in peer actions
        while (running) {
            System.out.println("1) List\n2) Details\n3) Check Active\n4) Simple Download\n5) LogOut\n");
            String option;
            // if the user gives invalid info then ask again
            do {
                System.out.print("> ");
                Scanner inp = new Scanner(System.in);
                option = inp.nextLine().toLowerCase();
                if (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5")) System.out.println("[-] Option \"" + option + "\" not found.");
            } while (!option.equals("1") && !option.equals("2") && !option.equals("3") && !option.equals("4") && !option.equals("5"));

            // manage options
            switch (option) {
                // option 1: list
                case "1":
                    // TODO: List
                    break;
                // option 2: details
                case "2":
                    // TODO: Details
                    break;
                // option 3: check active
                case "3":
                    // TODO: Check Active
                    break;
                // option 4: simple download
                case "4":
                    // TODO: Simple Download
                    break;
                // option 5: logout
                default:
                    logout(token);
                    running = false;
                    break;
            }
        }
    }

    /**
     * User LogOut.
     * This method is called if the user chose to log out.
     * Send request to tracker so the tracker can remove token_id of user
     */
    private void logout(int token) {
        // TODO: Send logout request to tracker
        // wait for response (temp response string below)
        String response = "[+] Your smelly ass has managed to logout, don't show up here never again or you'll be smoked on ma mama.";
        System.out.println(response);
    }

    public static void main(String[] args) {
        Peer peer = new Peer();
        peer.runPeer();

        try {
            ServerSocket server = new ServerSocket(6000);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new PeerThread(inConnection);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
