package com.eventledger.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {

        super("Account not found with id: " + accountId);
    }
}
