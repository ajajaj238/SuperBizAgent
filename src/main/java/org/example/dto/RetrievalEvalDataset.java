package org.example.dto;

import java.util.ArrayList;
import java.util.List;

public class RetrievalEvalDataset {

    private List<RetrievalEvalCase> cases = new ArrayList<>();

    public List<RetrievalEvalCase> getCases() {
        return cases;
    }

    public void setCases(List<RetrievalEvalCase> cases) {
        this.cases = cases;
    }

    public static class RetrievalEvalCase {
        private String id;
        private String query;
        private List<String> aliases = new ArrayList<>();
        private List<String> relevantSources = new ArrayList<>();
        private List<String> relevantFileNames = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public void setAliases(List<String> aliases) {
            this.aliases = aliases;
        }

        public List<String> getRelevantSources() {
            return relevantSources;
        }

        public void setRelevantSources(List<String> relevantSources) {
            this.relevantSources = relevantSources;
        }

        public List<String> getRelevantFileNames() {
            return relevantFileNames;
        }

        public void setRelevantFileNames(List<String> relevantFileNames) {
            this.relevantFileNames = relevantFileNames;
        }
    }
}
