package com.nju.comment.history;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.util.MethodRecordUtil;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MethodHistoryRepositoryImpl implements MethodHistoryRepository {

    private final ConcurrentHashMap<String, MethodRecord> history = new ConcurrentHashMap<>();

    private MethodHistoryRepositoryImpl() {
    }

    public static MethodHistoryRepositoryImpl getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final MethodHistoryRepositoryImpl INSTANCE = new MethodHistoryRepositoryImpl();
    }

    @Override
    public MethodRecord findByKey(String key) {
        return history.get(key);
    }

    @Override
    public void save(MethodRecord record) {
        String key = MethodRecordUtil.buildMethodKey(record.getQualifiedNameContainClass(), record.getSignature());
        history.put(key, record);
    }

    @Override
    public void deleteByKey(String key) {
        history.remove(key);
    }

    @Override
    public List<MethodRecord> findAll() {
        return history.values().stream().toList();
    }
}
