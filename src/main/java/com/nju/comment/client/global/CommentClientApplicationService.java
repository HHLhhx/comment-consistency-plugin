package com.nju.comment.client.global;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.TimeUnit;

@Service(Service.Level.APP)
public final class CommentClientApplicationService implements Disposable {

    private static final Logger LOG = Logger.getInstance(CommentClientApplicationService.class);

    public CommentClientApplicationService() {
        System.out.println("Initialize CommentClientApplicationService");
        CommentGeneratorClient.init("http://localhost:8080/api");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Boolean ok = CommentGeneratorClient.health().get(5, TimeUnit.SECONDS);
                LOG.info("Comment service health check: " + ok);
            } catch (Exception e) {
                LOG.warn("Comment service health check failed", e);
            }
        });
    }

    @Override
    public void dispose() {
        LOG.info("Dispose CommentClientApplicationService");
        CommentGeneratorClient.shutdown();
    }
}
