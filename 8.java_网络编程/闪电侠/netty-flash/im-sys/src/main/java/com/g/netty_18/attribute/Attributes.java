package com.g.netty_18.attribute;

import io.netty.util.AttributeKey;
import com.g.netty_18.session.Session;

public interface Attributes {
    AttributeKey<Session> SESSION = AttributeKey.newInstance("session");
}
