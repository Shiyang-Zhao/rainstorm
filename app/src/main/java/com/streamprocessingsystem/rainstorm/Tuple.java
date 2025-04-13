package com.streamprocessingsystem.rainstorm;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.json.JSONObject;

import com.streamprocessingsystem.util.Command;

public class Tuple {
    private final String tupleId;
    private final int nodeId;
    private final String key;
    private final String value;
    private String parameters;
    private final Command stage;

    public Tuple(int nodeId, String key, String value, String parameters, Command stage) {
        this.nodeId = nodeId;
        this.key = key;
        this.value = value;
        this.tupleId = UUID.nameUUIDFromBytes((key + "," + value).getBytes(StandardCharsets.UTF_8)).toString();
        this.parameters = parameters;
        this.stage = stage;
    }

    public Tuple(String tupleId, int nodeId, String key, String value, Command stage) {
        this.tupleId = tupleId;
        this.nodeId = nodeId;
        this.key = key;
        this.value = value;
        this.stage = stage;
    }

    public String getTupleId() {
        return tupleId;
    }

    public int getNodeId() {
        return nodeId;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getParameters() {
        return parameters;
    }

    public Command getStage() {
        return stage;
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("tupleId", tupleId)
                .put("nodeId", nodeId)
                .put("key", key)
                .put("value", value);
    }

    public static List<JSONObject> toJsonList(List<Tuple> tuples) {
        return tuples.stream()
                .map(Tuple::toJson)
                .collect(Collectors.toList());
    }
}
