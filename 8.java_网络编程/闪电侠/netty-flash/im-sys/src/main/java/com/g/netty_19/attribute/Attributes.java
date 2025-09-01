package com.g.netty_19.attribute;

import io.netty.util.AttributeKey;
import com.g.netty_19.session.Session;

public interface Attributes {
    AttributeKey<Session> SESSION = AttributeKey.newInstance("session");
}
