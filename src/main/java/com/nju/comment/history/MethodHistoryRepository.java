package com.nju.comment.history;

import com.nju.comment.dto.MethodRecord;

import java.util.List;

public interface MethodHistoryRepository {

    MethodRecord findByKey(String key);

    void save(MethodRecord record);

    void deleteByKey(String key);

    List<MethodRecord> findAll();
}
