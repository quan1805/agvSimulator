package com.rostek;

import com.google.gson.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AgvSimulator extends Thread {

    private static final Logger LOG = LogManager.getLogger(AgvSimulator.class);

    private ServerSocket listener = null;
    private Socket client;
    private BufferedReader is;
    private BufferedWriter os;

    private DataOutputStream dout;
    private DataInputStream din;
    private ScheduledExecutorService scheduler;
    private final Runnable stateTask;
    private final Thread transportOrderExecutor;
    private final LinkedList<String> todoList = new LinkedList<>();
    private final LinkedList<String> completedPoints = new LinkedList<>();

    private final Gson GSON = new Gson();
    private String position;
    private String lastTransportOrder;
    private int transportOrderCount = 0;

    public AgvSimulator(String initialPosition) {
        this.position = initialPosition;
        stateTask = () -> {
            if (client != null && client.isConnected() && dout != null) {

//        if (client != null && client.isConnected() && os != null) {
                try {
                    dout.writeUTF(createStateFeedback());
                    dout.flush();
//          os.write(createStateFeedback());
//          os.flush();
                } catch (IOException e) {
                    e.printStackTrace();
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

    public void open(int port) throws IOException {
        listener = new ServerSocket(port);
        LOG.info("Server opened in port " + port);
        this.start();
    }

    public void close() {
        scheduler.shutdown();
        try {
            client.close();
            listener.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        client = null;
        listener = null;
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
        while (true) {
            try {
                LOG.info("Waiting for client...");
                client = listener.accept();

                LOG.info("Client " + client.getRemoteSocketAddress() + " connected");
                LOG.info("Initializing IO...");

                din = new DataInputStream(client.getInputStream());
                dout = new DataOutputStream(client.getOutputStream());

                is = new BufferedReader(new InputStreamReader(client.getInputStream()));
                os = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));

                LOG.info("Creating state feedback interval task...");
                scheduler = Executors.newScheduledThreadPool(1);
                scheduler.scheduleAtFixedRate(stateTask, 1, 2000, TimeUnit.MILLISECONDS);

                LOG.info("Ready for request!");
                while (true) {
                    String transportOrder = din.readUTF();
                    try {
                        if (transportOrder == null) {
                            throw new IOException("Client disconnected");
                        }
                        if (Objects.equals(lastTransportOrder, transportOrder)) {
                            LOG.info("Duplicated transport order, ignored.");
                            continue;
                        }
                        JsonObject to = GSON.fromJson(transportOrder, JsonObject.class);
                        if (processTransportOrder(to)) {
                            LOG.info(MessageFormat.format("Executing transport order #{0} {1}",
                                    ++transportOrderCount,
                                    todoList));
                            setLastTransportOrder(transportOrder);
                        }
                    } catch (JsonSyntaxException ex) {
                        LOG.error("Invalid JSON: " + transportOrder);
                        ex.printStackTrace();
                    } catch (Exception ex) {
                        LOG.error(MessageFormat.format("{0} when handle request: {1} at {2}",
                                ex.getClass().getName(), ex.getMessage(), ex.getStackTrace()[0]));
                    }
                }
            } catch (IOException e) {
                scheduler.shutdown();
                try {
                    client.close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
                is = null;
                os = null;

                din = null;
                dout = null;

                e.printStackTrace();
                LOG.error("Caught exception, force close client.");
            }
        }
    }

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

    boolean processTransportOrder(JsonObject to) {
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
        ;
        transportOrderExecutor.start();

        return true;
    }
}
