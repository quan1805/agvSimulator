package com.rostek;

import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.IOException;

public class Starter {
  public static void main(String[] args) throws MqttException {
    AgvSimulator agv = new AgvSimulator("0");
    try {
      agv.open();
    } catch (IOException e) {
      agv.close();
    }
  }
}
