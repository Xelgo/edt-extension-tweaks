package ru.xelgo.edt.contextlinks.ui;

/**
 * Stored debugger expressions snapshot.
 */
final class VariableSnapshot
{
    final String id;
    final String timestamp;
    final String location;
    final int expressionCount;
    final String json;

    VariableSnapshot(String id, String timestamp, String location, int expressionCount, String json)
    {
        this.id = id;
        this.timestamp = timestamp;
        this.location = location;
        this.expressionCount = expressionCount;
        this.json = json;
    }
}
