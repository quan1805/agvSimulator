package com.rostek;

import com.google.gson.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AgvSimulator extends Thread implements MqttCallback {

    private static final Logger LOG = LogManager.getLogger(AgvSimulator.class);

    //socket
//    private ServerSocket listener = null;
//    private Socket client;

    //mqtt
    public static MqttAsyncClient myClient;

    private DataOutputStream dout;
    private DataInputStream din;
    private ScheduledExecutorService scheduler;
    private final Runnable stateTask;
    private Thread transportOrderExecutor;
    private final LinkedList<String> todoList = new LinkedList<>();
    private final LinkedList<String> completedPoints = new LinkedList<>();

    protected LinkedList<JsonObject> orderQueue = new LinkedList<>();

    private final Gson GSON = new Gson();
    private String position;
    private String lastTransportOrder;
    private int transportOrderCount = 0;

    public AgvSimulator(String initialPosition) {
        this.position = initialPosition;
        stateTask = () -> {
            if (myClient != null && myClient.isConnected() ) {
//            if (true) {
                try {
                    MqttMessage mqttMessage2 = new MqttMessage(createStateFeedback().getBytes());
                    myClient.publish("myTopicPub", mqttMessage2.getPayload(), 0 , false);;
                } catch (MqttPersistenceException e) {
                    throw new RuntimeException(e);
                } catch (MqttException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        transportOrderExecutor = new Thread(() -> {
            while (!todoList.isEmpty()) {
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ignored) {
                }
                String destination = todoList.poll();
                completedPoints.add(destination);
                setPosition(destination);
            }
            LOG.info("Transport order #" + transportOrderCount + " was executed.");
        });

    }

    public void open() throws IOException, MqttException {
        myClient = new MqttAsyncClient("tcp://localhost:1883", "myClient");
        myClient.setCallback(this);

        IMqttToken token = myClient.connect();
        token.waitForCompletion();
        LOG.info("Server opened...");
        this.start();
    }

    public void close() throws MqttException {
        scheduler.shutdown();
        myClient.disconnect();
    }

    public void setPosition(String position) {
        this.position = position;
        LOG.info("AGV reached position: " + position);
    }

    public void setLastTransportOrder(String lastTransportOrder) {
        this.lastTransportOrder = lastTransportOrder;
    }

    @Override
    public void run() {
//        if (!orderQueue.isEmpty()){
//            try {
//                JsonObject to =
//            }
//        }
            try {
                myClient.subscribe("myTopicSub", 0);

                LOG.info("Creating state feedback interval task...");
                scheduler = Executors.newScheduledThreadPool(1);
                scheduler.scheduleAtFixedRate(stateTask, 1, 2000, TimeUnit.MILLISECONDS);

                LOG.info("Ready for request!");
            } catch (MqttException e) {
                throw new RuntimeException(e);
            }
        }
//    }

    private String createStateFeedback() {
        JsonObject feedback = new JsonObject();
        feedback.addProperty("feedback", "STATUS_IND");

        JsonObject pos = new JsonObject();
        pos.addProperty("position_name", position);
        pos.addProperty("x", 0);
        pos.addProperty("y", 0);
        pos.addProperty("z", 0);
        feedback.add("position", pos);

        feedback.addProperty("orientation", 0);
        feedback.addProperty("status", 0);
        feedback.addProperty("action", 0);

        JsonArray todo = new JsonArray();
        todoList.forEach(todo::add);
        feedback.add("todo_list", todo);

        JsonArray completed = new JsonArray();
        completedPoints.forEach(completed::add);
        feedback.add("completed_point", completed);

        return feedback.toString() + "\n";
    }

    boolean processTransportOrder(JsonObject to) throws InterruptedException {

        try {
            if (!to.has("cmd")) {
                LOG.error("Transport order doesn't have element [cmd]");
                return false;
            }
            String cmd = to.get("cmd").getAsString();
            if (!Objects.equals(cmd, "runline")) {
                LOG.error("Transport order unknown command: " + cmd);
                return false;
            }
            if (!to.has("points")) {
                LOG.error("Transport order doesn't have element [points]");
                return false;
            }
            JsonArray points = to.get("points").getAsJsonArray();
            if (points == null || points.isJsonNull() || points.size() == 0) {
                LOG.error("Transport order doesn't have any point");
                return false;
            }
            LinkedList<String> incomingPoints = new LinkedList<>();
            for (JsonElement point : points) {
                JsonObject pointObject = point.getAsJsonObject();
                if (!pointObject.has("id")) {
                    LOG.error("Transport order invalid point: " + pointObject.toString());
                    return false;
                }
                incomingPoints.add(pointObject.get("id").getAsString());
            }
            todoList.clear();
            todoList.addAll(incomingPoints);
        } catch (IllegalStateException ex) {
            LOG.error(MessageFormat.format("Processing transport order {0} caught: {1} in {2}",
                    to.toString(),
                    ex.getMessage(),
                    ex.getStackTrace()[0].toString()));
            return false;
        }

        System.out.println( "State: "+ transportOrderExecutor.getState());
        transportOrderExecutor = new Thread(() -> {
            while (!todoList.isEmpty()) {
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ignored) {
                }
                String destination = todoList.poll();
                completedPoints.add(destination);
                setPosition(destination);
            }
            LOG.info("Transport order #" + transportOrderCount + " was executed.");
        });
        transportOrderExecutor.start();

        return true;
    }

    @Override
    public void connectionLost(Throwable throwable) {

    }

    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) {
            String transportOrder = mqttMessage.toString();
            try {
                if (transportOrder == null) {
                    throw new IOException("Client disconnected");
                }
                if (Objects.equals(lastTransportOrder, transportOrder)) {
                    LOG.info("Duplicated transport order, ignored.");
//                    continue;
                }
                JsonObject to = GSON.fromJson(transportOrder, JsonObject.class);

//                orderQueue.add(to);
//
//                System.out.println("Added transport order to queue");

                if (processTransportOrder(to)) {
                    LOG.info(MessageFormat.format("Executing transport order #{0} {1}",
                            ++transportOrderCount,
                            todoList));
                    setLastTransportOrder(transportOrder);
                }
            } catch (JsonSyntaxException ex) {
                LOG.error("Invalid JSON: " + transportOrder);
            } catch (Exception ex) {
                LOG.error(MessageFormat.format("{0} when handle request: {1} at {2}",
                        ex.getClass().getName(), ex.getMessage(), ex.getStackTrace()[0]));
            }
        }
//    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
