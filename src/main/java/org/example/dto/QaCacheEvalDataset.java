package org.example.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * QA 缓存阈值评测数据集。
 * 三种用例：
 * - HIT：query 应命中 canonical（近义改写）；
 * - MISS：query 不得命中 canonical（近形但不同主题，用于校准阈值防误命中）；
 * - DYNAMIC：动态/时效问题，读侧闸门应直接拦截。
 */
public class QaCacheEvalDataset {

    private List<String> canonicals = new ArrayList<>();
    private List<QaCacheEvalCase> cases = new ArrayList<>();

    public List<String> getCanonicals() {
        return canonicals;
    }

    public void setCanonicals(List<String> canonicals) {
        this.canonicals = canonicals;
    }

    public List<QaCacheEvalCase> getCases() {
        return cases;
    }

    public void setCases(List<QaCacheEvalCase> cases) {
        this.cases = cases;
    }

    public static class QaCacheEvalCase {
        private String id;
        private String query;
        /** HIT：应命中的 canonical；MISS：不得命中的 decoy；DYNAMIC：忽略 */
        private String canonical;
        private String expected;
        private String note;

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

        public String getCanonical() {
            return canonical;
        }

        public void setCanonical(String canonical) {
            this.canonical = canonical;
        }

        public String getExpected() {
            return expected;
        }

        public void setExpected(String expected) {
            this.expected = expected;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
