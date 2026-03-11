package com.nju.comment.history;

import com.nju.comment.pojo.MethodRecord;

import java.util.List;

public interface MethodHistoryRepository {

    void clear();

    MethodRecord findByKey(String key);

    void save(MethodRecord record);

    void deleteByKey(String key);

    List<MethodRecord> findAll();
}
