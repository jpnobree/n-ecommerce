package com.atelier.notification;

/** Publicado dentro de uma transação; o e-mail só sai depois do commit (ver {@link EmailSender}). */
public record EmailRequested(String to, String subject, String body) {
}
