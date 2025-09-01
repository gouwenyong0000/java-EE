package com.g.netty_20.attribute;

import io.netty.util.AttributeKey;
import com.g.netty_20.session.Session;

public interface Attributes {
    AttributeKey<Session> SESSION = AttributeKey.newInstance("session");
}
