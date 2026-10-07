package com.benxin.exp.database.domain;

import java.sql.Timestamp;

/** 客户。 */
public record Customer(long id, String name, String level, String phone, Timestamp createdAt) {
}
