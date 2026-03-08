package com.helpinminutes.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_PHOTOS = "photos.exchange";
    public static final String QUEUE_PHOTO_UPLOADED = "photo.uploaded.queue";
    public static final String ROUTING_KEY_PHOTO_UPLOADED = "photo.uploaded";

    public static final String QUEUE_PHOTO_DLQ = "photo.dlq";
    public static final String ROUTING_KEY_PHOTO_DLQ = "photo.dlq";

    public static final String EXCHANGE_NOTIFICATIONS = "notifications.exchange";
    public static final String QUEUE_NOTIFICATION_SEND = "notifications.send.queue";
    public static final String ROUTING_KEY_NOTIFICATION_SEND = "notifications.send";
    public static final String QUEUE_NOTIFICATION_DLQ = "notifications.dlq";
    public static final String ROUTING_KEY_NOTIFICATION_DLQ = "notifications.dlq";

    public static final String EXCHANGE_KYC = "him.kyc";
    public static final String QUEUE_KYC_PROCESSING = "kyc.processing.queue";
    public static final String ROUTING_KEY_KYC_PROCESSING = "kyc.processing";
    public static final String QUEUE_KYC_DLQ = "kyc.dlq";
    public static final String ROUTING_KEY_KYC_DLQ = "kyc.dlq";

    @Bean
    public DirectExchange photosExchange() {
        return new DirectExchange(EXCHANGE_PHOTOS);
    }

    @Bean
    public Queue photoUploadedQueue() {
        return QueueBuilder.durable(QUEUE_PHOTO_UPLOADED)
                .withArgument("x-dead-letter-exchange", EXCHANGE_PHOTOS)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_PHOTO_DLQ)
                .build();
    }

    @Bean
    public Queue photoDlq() {
        return QueueBuilder.durable(QUEUE_PHOTO_DLQ).build();
    }

    @Bean
    public Binding photoUploadedBinding(Queue photoUploadedQueue, DirectExchange photosExchange) {
        return BindingBuilder.bind(photoUploadedQueue).to(photosExchange).with(ROUTING_KEY_PHOTO_UPLOADED);
    }

    @Bean
    public Binding photoDlqBinding(Queue photoDlq, DirectExchange photosExchange) {
        return BindingBuilder.bind(photoDlq).to(photosExchange).with(ROUTING_KEY_PHOTO_DLQ);
    }

    @Bean
    public DirectExchange notificationsExchange() {
        return new DirectExchange(EXCHANGE_NOTIFICATIONS);
    }

    @Bean
    public Queue notificationSendQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATION_SEND)
                .withArgument("x-dead-letter-exchange", EXCHANGE_NOTIFICATIONS)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_NOTIFICATION_DLQ)
                .build();
    }

    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(QUEUE_NOTIFICATION_DLQ).build();
    }

    @Bean
    public Binding notificationSendBinding(Queue notificationSendQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(notificationSendQueue).to(notificationsExchange).with(ROUTING_KEY_NOTIFICATION_SEND);
    }

    @Bean
    public Binding notificationDlqBinding(Queue notificationDlq, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(notificationDlq).to(notificationsExchange).with(ROUTING_KEY_NOTIFICATION_DLQ);
    }

    @Bean
    public DirectExchange kycExchange() {
        return new DirectExchange(EXCHANGE_KYC);
    }

    @Bean
    public Queue kycProcessingQueue() {
        return QueueBuilder.durable(QUEUE_KYC_PROCESSING)
                .withArgument("x-dead-letter-exchange", EXCHANGE_KYC)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_KYC_DLQ)
                .build();
    }

    @Bean
    public Queue kycDlq() {
        return QueueBuilder.durable(QUEUE_KYC_DLQ).build();
    }

    @Bean
    public Binding kycProcessingBinding(Queue kycProcessingQueue, DirectExchange kycExchange) {
        return BindingBuilder.bind(kycProcessingQueue).to(kycExchange).with(ROUTING_KEY_KYC_PROCESSING);
    }

    @Bean
    public Binding kycDlqBinding(Queue kycDlq, DirectExchange kycExchange) {
        return BindingBuilder.bind(kycDlq).to(kycExchange).with(ROUTING_KEY_KYC_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

}
