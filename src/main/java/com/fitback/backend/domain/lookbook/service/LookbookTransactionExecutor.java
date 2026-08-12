package com.fitback.backend.domain.lookbook.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LookbookTransactionExecutor {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T execute(Supplier<T> action) {
        return action.get();
    }
}
