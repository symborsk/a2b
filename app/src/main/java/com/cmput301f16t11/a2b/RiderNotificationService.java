package com.cmput301f16t11.a2b;

import android.app.DownloadManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.searchly.jestdroid.DroidClientConfig;
import com.searchly.jestdroid.JestClientFactory;
import com.searchly.jestdroid.JestDroidClient;

import java.util.ArrayList;
import java.util.Map;

import io.searchbox.client.JestResult;
import io.searchbox.core.Get;

/**
 * This class is a background service that get runs when the user as a rider creates an active request. It
 * continuously checks the server for that specific request and send a notification if there is an added
 * driver that accepts that ride.
 * Will be terminated when the user
 */
public class RiderNotificationService extends IntentService {

    //Elastic search stuff
    private static JestDroidClient client;
    private static String index = "f16t11";

    private static String openRequest = "openRequest";
    private static String user = "user";
    private static ArrayList<UserRequest> requestMonitoring = new ArrayList<>();
    private static RiderNotificationService self;

    public RiderNotificationService(){
        super("Driver Notification service");
        this.self = this;
    }

    @Override
    protected void onHandleIntent(Intent workIntent) {
        verifySettings();
        while(true) {
            //Added to avoid editing this list while the background thread looks at it
            synchronized (requestMonitoring) {
                for (UserRequest request : requestMonitoring) {
                    ArrayList<String> acceptedDrivers = request.getAcceptedDriverIDs();
                    ArrayList<String> serverAcceptedDrivers = getAcceptedDriversFromId(request.getId());
                    ArrayList<String> differentUser = findDifferenceRequests(acceptedDrivers, serverAcceptedDrivers);

                    //If there is an accepted user
                    if (differentUser.size() != 0) {
                        sendNotificationOfAcceptedDriver(serverAcceptedDrivers, request.getId());

                        for (String userId: differentUser) {
                            addDriverToMonitor(request, userId);
                        }
                    }
                }
            }

            //We need this here so we aren't constantly hogging resource and communication with server
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * This is static call that allows the user to retrieve a valid intent to start a service
     */
    public static Intent createIntentStartNotificationService(Context context) {

        Intent intent = new Intent(context, RiderNotificationService.class);
        return intent;
    }

    /**
     * determines if a service is started so there is only one service at any point
     * @return
     */
    public static Boolean isRecieveServiceStarted(){

        if(self == null){
            return false;
        }
        return true;
    }

    /**
     * Adds a request to monitor on the driver side
     *
     * @param request request to monitor
     * @param userId the driver
     */
    public void addDriverToMonitor(UserRequest request, String userId){
        int index = requestMonitoring.indexOf(request);

        synchronized (requestMonitoring) {

            requestMonitoring.get(index).addAcceptedDriver(userId);
        }
    }

    /**
     * Add a request to be notified about
     *
     * @param request the request to be notified about
     */
    public static void addRequestToBeNotified(UserRequest request){

       synchronized (requestMonitoring){
           requestMonitoring.add(request);
       }
    }

    /**
     * Removes a request from the request that are being montiored
     */
    public static void endNotification(String id){

        //Ensure that the request monitor list is synchronized
        synchronized (requestMonitoring) {
            //Remove the service
            for(int i = 0; i < requestMonitoring.size() ; i++){
                if (requestMonitoring.get(i).getId().equals(id)) {
                    requestMonitoring.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * This is from the ElasticRequestController it sets up the Droid client object
     */
    private void verifySettings() {
        // Initialize client if necessary
        if (client == null) {
            DroidClientConfig.Builder builder = new DroidClientConfig.Builder("http://35.164.200.4:9200");
            DroidClientConfig config = builder.build();

            JestClientFactory factory = new JestClientFactory();
            factory.setDroidClientConfig(config);
            client = (JestDroidClient) factory.getObject();
        }
    }

    /**
     * This method return a collection of all the user that are in the server users but not in the original user
     * @param userOriginal
     * @param userServer
     * @return list of accepted drivers for that request
     */
    private ArrayList<String> findDifferenceRequests(ArrayList<String> userOriginal, ArrayList<String> userServer){
        if(userServer == null){
            return new ArrayList<>();
        }

        for(String userOr : userOriginal){
            for(int i = 0; i< userServer.size();){
                if(userServer.get(i).equals(userOr)){
                    userServer.remove(i);
                }
                else{
                    i++;
                }
            }
        }

        return userServer;
    }

    /**
     * This is a method from the ElasticsearchRequestController as well that get the accepted drivers from
     * a specfiic request id.
     * @param requestId
     * @returnD
     */
    private ArrayList<String> getAcceptedDriversFromId(String requestId){
        verifySettings();

        Get get = new Get.Builder(index, requestId).type(openRequest).build();
        UserRequest userRequest;

        try {
            JestResult result = client.execute(get);
            if (result.isSucceeded()) {
                userRequest = result.getSourceAsObject(UserRequest.class);
            }
            else{
                Log.i("Error", "Failed to find any accepted requests");
                return new ArrayList<>();
            }
        } catch (Exception e) {
            Log.i("Error", "Failed to communicate with elasticsearch server");
            e.printStackTrace();
            return null;
        }

        return userRequest.getAcceptedDriverIDs();
    }

    /**
     * This send a notification that all the addedUsers have accepted that individual rider's request
     *
     * @param addedUsers
     */
    private  void sendNotificationOfAcceptedDriver(ArrayList<String> addedUsers, String requestId){
        String notification = "";
        for(int i = 0; i < addedUsers.size(); i++){
            notification += getUserName(addedUsers.get(i)) + " & ";
        }

        //Remove the last 3c chars " & " as we only want that inbetween driver usernames
        notification = notification.substring(0, notification.length()-3);
        notification = notification + " has accepted  your request ";

        Notification noti = new Notification.Builder(this)
                .setContentTitle(notification)
                .setSmallIcon(R.drawable.ic_notification_a2b)
                .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // hide the notification after its selected
        noti.flags |= Notification.FLAG_AUTO_CANCEL;
        notificationManager.notify(0, noti);
    }

    /**
     * Gets a username from a user id
     *
     * @param usernameId user id (string)
     * @return the username corresponding to usernameId
     */
    private String getUserName(String usernameId){
        Get get = new Get.Builder(index, usernameId).type(user).build();
        User user;
        try {
            JestResult result = client.execute(get);
            if (result.isSucceeded()) {
                user = result.getSourceAsObject(User.class);
                return user.getName();
            }
            else{
                Log.i("Error", "Failed to find any accepted requests");
                return "<username not found>";
            }
        } catch (Exception e) {
            Log.i("Error", "Failed to communicate with elasticsearch server");
            e.printStackTrace();
            return "<username not found>";
        }
    }
}

//public class DriverNotificationService extends IntentService {
//    private static User driver;
//    private static ArrayList<UserRequest> requests = new ArrayList<>();
//
//    //Elastic search stuff
//    private static JestDroidClient client;
//    private static String index = "f16t11";
//
//    private static String type = "inProgress";
//    private static DriverNotificationService self;
//
//    public DriverNotificationService(){
//        super("Driver Notification service");
//        this.self = this;
////        driver = UserController.getUser();
//    }
//
////    public DriverNotificationService(ArrayList<UserRequest> requests) {
////        super("intent service");
////        this.requests = requests;
////        driver = UserController.getUser();
////        self = this;
////    }
//
//    @Override
//    protected void onHandleIntent(Intent workIntent) {
//        verifySettings();
//        while(true) {
//            synchronized (requests) {
//                // Iterator idea taken to avoid exceptions while removing inside for loop
//                // Taken from the link below on November 18, 2016
//                // http://stackoverflow.com/questions/223918/iterating-through-a-collection-avoiding-concurrentmodificationexception-when-re
//                for (Iterator<UserRequest> iterator = requests.iterator(); iterator.hasNext(); ) {
//                    UserRequest request = iterator.next();
//                    UserRequest serverRequest = getInProgressRequest(request.getId());
//                    if (serverRequest != null) {
//                        //If the driver is accepted notify him also notify the drivers that are not chose
//                        if (serverRequest.getConfirmedDriver().equals(driver.getId())) {
//                            sendNotificationOfRiderConfirmed(serverRequest);
//                        } else {
//                            sendNotificationOfRiderRejected(serverRequest);
//                        }
//                        iterator.remove();
//                    }
//                }
//
//                // If there are no more requests stop the service
//                if (requests.size() == 0) {
//                    stopSelf();
//                }
//            }
//
//            // Wait 3s before trying again
//            try {
//                Thread.sleep(10000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//       }
//    }
//
//    /**
//     * This is static call that allows the user to retrieve a valid intent to start a service
//     *
//     * @param context the context of which the intent should be created form
//     */
//    public static Intent createIntentDriverNotificationService(Context context) {
//        Intent intent = new Intent(context, DriverNotificationService.class);
//        return intent;
//    }
//
//    /**
//     * This is from the ElasticRequestController it sets up the Droid client object
//     */
//    private void verifySettings() {
//        // Initialize client if necessary
//        if (client == null) {
//            DroidClientConfig.Builder builder = new DroidClientConfig.Builder("http://35.164.200.4:9200");
//            DroidClientConfig config = builder.build();
//
//            JestClientFactory factory = new JestClientFactory();
//            factory.setDroidClientConfig(config);
//            client = (JestDroidClient) factory.getObject();
//        }
//    }
//
//    /**
//     * This is a method from the ElasticsearchRequestController that finds thein progress request from
//     * the server is there is any.
//     * @param requestId id of the desired request
//     * @return the request object corresponding to the requestId argument from inProgress
//     */
//    private UserRequest getInProgressRequest(String requestId){
//        verifySettings();
//        Get get = new Get.Builder(index, requestId).type(type).build();
//
//        UserRequest userRequest;
//        try {
//            JestResult result = client.execute(get);
//            if (result.isSucceeded()) {
//                userRequest = result.getSourceAsObject(UserRequest.class);
//                return userRequest;
//            }
//            else{
//                return null;
//            }
//        } catch (Exception e) {
//            Log.i("Error", "Failed to communicate with elasticsearch server");
//            e.printStackTrace();
//            return null;
//        }
//
//    }
//
//    /**
//     * Sends a notification to the phone that the rider has accepted the driver ride.
//     *
//     * @param request the request that a notification will be sent regarding
//     */
//    private void sendNotificationOfRiderConfirmed(UserRequest request){
//        String notification = request.getRider()+ " has accepted your ride for request " + request.getId();
//
//        Notification noti = new Notification.Builder(this)
//                .setContentTitle(notification)
//                .setSmallIcon(R.drawable.common_plus_signin_btn_icon_dark)
//                .build();
//
//        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//
//        // hide the notification after its selected
//        noti.flags |= Notification.FLAG_AUTO_CANCEL;
//
//        notificationManager.notify(0, noti);
//
//    }
//
//    /**
//     * Sends a notification to the current phone that the rider had rejected the ride the driver
//     * offered.
//     *
//     * @param request the request that has been rejected
//     */
//    private  void sendNotificationOfRiderRejected(UserRequest request){
//        String notification = request.getRider()+ " has taken another ride for request " + request.getId();
//
//        Notification noti = new Notification.Builder(this)
//                .setContentTitle(notification)
//                .setSmallIcon(R.drawable.common_plus_signin_btn_icon_dark)
//                .build();
//
//        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//
//        // hide the notification after its selected
//        noti.flags |= Notification.FLAG_AUTO_CANCEL;
//
//        notificationManager.notify(0, noti);
//    }
//
//    public static void addRequest(UserRequest req) {
//        synchronized (requests) {
//            requests.add(req);
//        }
//    }
//
//    public static Boolean isStarted() {
//        if (self==null) {
//            return false;
//        }
//        return true;
//    }
//
//
//    public static void serviceHandler(UserRequest req, Activity activity) {
//        if (!DriverNotificationService.isStarted()) {
//            Intent intent = createIntentDriverNotificationService(activity);
//            activity.startService(intent);
//        }
//
//        DriverNotificationService.self.addRequest(req);
//    }
//
//}
