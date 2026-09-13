package com.example.instrument.connection;

import com.example.instrument.api.InstrumentClient;
import com.example.instrument.config.ClientConfig;
import com.example.instrument.core.InstrumentClientImpl;
import com.example.instrument.protocol.Protocol;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionManager implements AutoCloseable {
    private final Map<UUID,InstrumentClient> clients=new ConcurrentHashMap<>();
    public UUID add(InetSocketAddress address,Protocol protocol,ClientConfig config){UUID id=UUID.randomUUID();clients.put(id,new InstrumentClientImpl(address,protocol,config));return id;}
    public InstrumentClient get(UUID id){InstrumentClient c=clients.get(id);if(c==null)throw new NoSuchElementException("client not found: "+id);return c;}
    public Optional<InstrumentClient> find(UUID id){return Optional.ofNullable(clients.get(id));}
    public void remove(UUID id){InstrumentClient c=clients.remove(id);if(c!=null)c.close();}
    public Set<UUID> ids(){return Set.copyOf(clients.keySet());}
    @Override public void close(){clients.values().forEach(InstrumentClient::close);clients.clear();}
}
