package com.rostek;

import java.io.IOException;

public class Starter {
  public static void main(String[] args) {
    AgvSimulator agv = new AgvSimulator("0");
    try {
      agv.open(5000);
    } catch (IOException e) {
      agv.close();
    }
  }
}
