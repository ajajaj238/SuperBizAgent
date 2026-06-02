package org.example.service.intent;

import java.util.List;
import java.util.Map;

public interface IntentClassifier {
    IntentResult classify(String userInput, List<Map<String, String>> history);
}
