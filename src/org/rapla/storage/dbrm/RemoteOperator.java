/*--------------------------------------------------------------------------*
 | Copyright (C) 2006  Christopher Kohlhaas                                 |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/

package org.rapla.storage.dbrm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;

import org.rapla.ConnectInfo;
import org.rapla.components.util.Assert;
import org.rapla.components.util.Cancelable;
import org.rapla.components.util.Command;
import org.rapla.components.util.CommandScheduler;
import org.rapla.components.util.DateTools;
import org.rapla.components.util.SerializableDateTimeFormat;
import org.rapla.components.util.TimeInterval;
import org.rapla.entities.Entity;
import org.rapla.entities.EntityNotFoundException;
import org.rapla.entities.RaplaType;
import org.rapla.entities.User;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.domain.Appointment;
import org.rapla.entities.domain.AppointmentStartComparator;
import org.rapla.entities.domain.Reservation;
import org.rapla.entities.domain.internal.AllocatableImpl;
import org.rapla.entities.domain.internal.AppointmentImpl;
import org.rapla.entities.domain.internal.ReservationImpl;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.internal.UserImpl;
import org.rapla.facade.Conflict;
import org.rapla.facade.UpdateModule;
import org.rapla.facade.internal.ConflictImpl;
import org.rapla.framework.Configuration;
import org.rapla.framework.Disposable;
import org.rapla.framework.RaplaContext;
import org.rapla.framework.RaplaException;
import org.rapla.framework.internal.ContextTools;
import org.rapla.framework.logger.Logger;
import org.rapla.rest.gwtjsonrpc.common.AsyncCallback;
import org.rapla.rest.gwtjsonrpc.common.FutureResult;
import org.rapla.storage.RaplaSecurityException;
import org.rapla.storage.UpdateEvent;
import org.rapla.storage.UpdateResult;
import org.rapla.storage.impl.AbstractCachableOperator;

/** This operator can be used to modify and access data over the
 * network.  It needs an server-process providing the StorageService
 * (usually this is the default rapla-server).
 * <p>Sample configuration:
  <pre>
   &lt;remote-storage id="web">
   &lt;/remote-storate>
  </pre>
*/
public class RemoteOperator  extends  AbstractCachableOperator implements  RestartServer,Disposable
{
    private boolean bSessionActive = false;
    String userId;
	RemoteServer remoteServer;
	RemoteStorage remoteStorage;
	protected CommandScheduler commandQueue;
	
	Date lastSyncedTimeLocal;
    Date lastSyncedTime;
    int timezoneOffset;
    ConnectInfo connectInfo;
    Configuration config;
    RemoteConnectionInfo connectionInfo;
	
    public RemoteOperator(RaplaContext context, Logger logger, Configuration config, RemoteServer remoteServer, RemoteStorage remoteStorage) throws RaplaException {
        super( context, logger );
        this.config = config;
        this.remoteServer = remoteServer;
        this.remoteStorage = remoteStorage;
    	commandQueue = context.lookup( CommandScheduler.class);
    	this.connectionInfo = new RemoteConnectionInfo();
    	remoteStorage.setConnectInfo( connectionInfo );
    	remoteServer.setConnectInfo( connectionInfo );
    	if ( config != null)
    	{
    	    String serverConfig  = config.getChild("server").getValue("${downloadServer}");
    	    final String serverURL= ContextTools.resolveContext(serverConfig, context );
    	    connectionInfo.setServerURL(serverURL);
    	}
    }
    
    public RemoteConnectionInfo getRemoteConnectionInfo()
    {
        return connectionInfo;
    }
    
    synchronized public void connect(ConnectInfo connectInfo) throws RaplaException {
        if ( connectInfo == null)
        {
            throw new RaplaException("RemoteOperator doesn't support anonymous connect");
        }
       
        if (isConnected())
            return;
        getLogger().info("Connecting to server and starting login..");
    	Lock writeLock = writeLock();
    	try
    	{
    		loginAndLoadData(connectInfo);
    		connectionInfo.setReAuthenticateCommand(new FutureResult<String>() {

    	            @Override
    	            public String get() throws Exception {
    	                getLogger().info("Refreshing access token.");
    	                return login();
    	            }

    	            @Override
    	            public String get(long wait) throws Exception {
    	                return get();
    	            }

    	            @Override
    	            public void get(AsyncCallback<String> callback) {
    	               try {
    	                   String string = get();
    	                   callback.onSuccess(string);
    	               } catch (Exception e) {
    	                   callback.onFailure(e);
    	               } 
    	            }
    	        });
    		initRefresh();
    	}
    	finally
    	{
    		unlock(writeLock);
    	}
    }

	
	private void loginAndLoadData(ConnectInfo connectInfo) throws RaplaException {
		this.connectInfo = connectInfo;
		String username = this.connectInfo.getUsername();
		login();
        getLogger().info("login successfull");
		loadData(username);
        bSessionActive = true;
	}

	protected String login() throws RaplaException {
		String connectAs = this.connectInfo.getConnectAs();
		String password = new String( this.connectInfo.getPassword());
		String username = this.connectInfo.getUsername();
		try {
		    RemoteServer serv1 = getRemoteServer();
		    LoginTokens loginToken = serv1.login(username,password, connectAs).get();
            String accessToken = loginToken.getAccessToken();
            if ( accessToken != null)
            {
		        connectionInfo.setAccessToken( accessToken);
	            return accessToken;
            }
            else
            {
                throw new RaplaSecurityException("Invalid Access token");
            }
		} catch (RaplaException ex){
		    disconnect();
		    throw ex;
		} catch (Exception ex){
		    disconnect();
		    throw new RaplaException(ex);
		}
	}
   
	public Date getCurrentTimestamp() {
	    if (lastSyncedTime == null)
	    {
	        return new Date(System.currentTimeMillis());
	    }
		// no matter what the client clock says we always sync to the server clock
		long passedMillis =  System.currentTimeMillis()- lastSyncedTimeLocal.getTime();
		if ( passedMillis < 0)
		{
			passedMillis = 0;
		}
		long correctTime = this.lastSyncedTime.getTime() + passedMillis;
		Date date = new Date(correctTime);
		return date;
	}
	
	public Date today() {
		long time = getCurrentTimestamp().getTime();
		Date raplaTime = new Date(time + timezoneOffset);
		return DateTools.cutDate( raplaTime);
	}

	Cancelable timerTask;
	int intervalLength;
	private final void initRefresh() 
	{
		Command refreshTask = new Command() {
			public void execute() {
			    try {
			        // test if the remote operator is writable
			        // if not we skip until the next update cycle
		            Lock writeLock = lock.writeLock();
                    boolean tryLock = writeLock.tryLock();
		            if ( tryLock)
		            {
		                writeLock.unlock();
		            }
                    if (isConnected() && tryLock) {
		                refresh();
		            }
			    } catch (RaplaConnectException e) {
                    getLogger().error("Error connecting " + e.getMessage());
			    } catch (RaplaException e) {
			        getLogger().error("Error refreshing.", e);
			    }
			}
		};
		intervalLength = UpdateModule.REFRESH_INTERVAL_DEFAULT;
		if (isConnected()) {
			try {
				intervalLength = getPreferences(null, true).getEntryAsInteger(UpdateModule.REFRESH_INTERVAL_ENTRY, UpdateModule.REFRESH_INTERVAL_DEFAULT);
			} catch (RaplaException e) {
				getLogger().error("Error refreshing.", e);
			}
		}
		if ( timerTask != null)
		{
			timerTask.cancel();
		}
		timerTask = commandQueue.schedule(refreshTask, 0, intervalLength);
	}

	public void dispose() 
	{
		if ( timerTask != null)
		{
			timerTask.cancel();
		}
	}
	
//    public String getConnectionName() {
//    	if ( connector != null)
//    	{
//    		return connector.getInfo();
//    	}
//    	else
//    	{
//    		return "standalone";
//    	}
//    }
//
//    private void doConnect() throws RaplaException {
//        boolean bFailed = true;
//        try {
//            bFailed = false;
//        } catch (Exception e) {
//            throw new RaplaException(i18n.format("error.connect",getConnectionName()),e);
//        } finally {
//            if (bFailed)
//                disconnect();
//        }
//    }

    public boolean isConnected() {
        return bSessionActive;
    }

    public boolean supportsActiveMonitoring() {
        return true;
    }

    synchronized public void refresh() throws RaplaException {
        String clientRepoVersion = getClientRepoVersion();
        RemoteStorage serv = getRemoteStorage();
    	try
        {
	        UpdateEvent evt = serv.refresh( clientRepoVersion).get();
	        refresh( evt);
        }
        catch (EntityNotFoundException ex)
        {
        	getLogger().error("Refreshing all resources due to " + ex.getMessage(), ex);
        	refreshAll();
        }
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
    }

    private String getClientRepoVersion() {
        return SerializableDateTimeFormat.INSTANCE.formatTimestamp(lastSyncedTime);
    }
 
    synchronized public void restartServer() throws RaplaException {
    	getLogger().info("Restart in progress ...");
    	String message = i18n.getString("restart_server");
  //      isRestarting = true;
    	try
    	{
	        RemoteStorage serv = getRemoteStorage();
	        serv.restartServer().get();
	        fireStorageDisconnected(message);
    	}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		

    }
   
    synchronized public void disconnect() throws RaplaException { 
    	connectionInfo.setAccessToken( null);
    	this.connectInfo = null;
    	connectionInfo.setReAuthenticateCommand(null);
        disconnect("Disconnection from Server initiated");
    }
    
    /** disconnect from the server */
    synchronized public void disconnect(String message) throws RaplaException {
        boolean wasConnected = bSessionActive;
    	getLogger().info("Disconnecting from server");
        try {
            bSessionActive = false;
            cache.clearAll();
        } catch (Exception e) {
            throw new RaplaException("Could not disconnect", e);
        }
        if ( wasConnected)
        {
            RemoteServer serv1 = getRemoteServer();
            try 
            {
            	serv1.logout().get();
            }
            catch (RaplaConnectException ex)
            {
            	getLogger().warn( ex.getMessage());
            }
    	    catch (RaplaException ex)
    	    {
    	    	throw ex;
    	    }
    	    catch (Exception ex)
    	    {
    	    	throw new RaplaException(ex);
    	    }		
        	fireStorageDisconnected(message);
        }
    }

    
    @Override
    protected void setResolver(Collection<? extends Entity> entities) throws RaplaException {
    	// don't resolve entities in standalone mode
    	if (context.has(RemoteMethodStub.class))
    	{
    		return;
    	}
    	super.setResolver(entities);
    }
    
    @Override
    protected void testResolve(Collection<? extends Entity> entities) {
        //  don't resolve entities in standalone mode
        if (context.has(RemoteMethodStub.class))
        {
            return;
        }
        super.testResolve(entities);
    }

    private void loadData(String username) throws RaplaException {
        getLogger().debug("Getting Data..");
        RemoteStorage serv = getRemoteStorage();
        try
        {
	        UpdateEvent evt = serv.getResources().get();
			updateTimestamps(evt);
	        Collection<Entity> storeObjects = evt.getStoreObjects();
        	cache.clearAll();
        	
        	testResolve( storeObjects);
        	setResolver( storeObjects );
            for( Entity entity:storeObjects) {
				if ( isStorableInCache(entity))
				{
					cache.put(entity);
				}
	        }
	        if ( username != null)
	        {
	        	UserImpl user = cache.getUser( username);
	        	userId = user.getId();
	        }
	        getLogger().debug("Data flushed");
        }
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
    }

	public void updateTimestamps(UpdateEvent evt) throws RaplaException {
		if ( evt.getLastValidated() == null)
		{
			throw new RaplaException("Server sync time is missing");
		}
		lastSyncedTimeLocal = new Date(System.currentTimeMillis());
		lastSyncedTime = evt.getLastValidated();
		timezoneOffset = evt.getTimezoneOffset();
		//long offset = TimeZoneConverterImpl.getOffset( DateTools.getTimeZone(), systemTimeZone, time);

	}

    protected void checkConnected() throws RaplaException {
        if ( !bSessionActive ) {
            throw new RaplaException("Not logged in or connection closed!");
        }
    }
   
    public void dispatch(UpdateEvent evt) throws RaplaException {
        checkConnected();
        // Store on server
        if (getLogger().isDebugEnabled()) {
            Iterator<Entity>it =evt.getStoreObjects().iterator();
            while (it.hasNext()) {
                Entity entity = it.next();
                getLogger().debug("dispatching store for: " + entity);
            }
            it =evt.getRemoveObjects().iterator();
            while (it.hasNext()) {
                Entity entity = it.next();
                getLogger().debug("dispatching remove for: " + entity);
            }
        }
        RemoteStorage serv = getRemoteStorage();
        evt.setLastValidated(lastSyncedTime);
        try
        {
        	UpdateEvent serverClosure =serv.dispatch( evt ).get();
        	refresh(serverClosure);
        } 
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }
    }
    
    public String[] createIdentifier(RaplaType raplaType, int count) throws RaplaException {
    	RemoteStorage serv = getRemoteStorage();
    	try
    	{
	    	List<String> id = serv.createIdentifier(raplaType.getLocalName(), count).get();
	    	return id.toArray(new String[] {});
    	} 
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }
    }

	private RemoteStorage getRemoteStorage() {
		return remoteStorage;
	}
	
	private RemoteServer getRemoteServer()  {
		return remoteServer;
	}

    public boolean canChangePassword() throws RaplaException  {
        RemoteStorage remoteMethod = getRemoteStorage();
        try
        {
	        String canChangePassword = remoteMethod.canChangePassword().get();
			boolean result = canChangePassword != null && canChangePassword.equalsIgnoreCase("true");
	        return result;
        } 
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }
    }

    @Override
    public void changePassword(User user,char[] oldPassword,char[] newPassword) throws RaplaException {
        try {
        	RemoteStorage remoteMethod = getRemoteStorage();
	        String username = user.getUsername();
			remoteMethod.changePassword(username, new String(oldPassword),new String(newPassword)).get();
		    refresh();
        } 
        catch (RaplaSecurityException ex) 
        {
        	throw new RaplaSecurityException(i18n.getString("error.wrong_password"));
        }
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }
    }
    
    @Override
    public void changeEmail(User user, String newEmail) throws RaplaException 
    {
    	try
    	{
	    	RemoteStorage remoteMethod = getRemoteStorage();
	        String username = user.getUsername();
			remoteMethod.changeEmail(username,newEmail).get();
	        refresh();
    	}
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }		
    }
    
    @Override
	public void confirmEmail(User user, String newEmail)	throws RaplaException 
	{
    	try
    	{
	    	RemoteStorage remoteMethod = getRemoteStorage();
	        String username = user.getUsername();
			remoteMethod.confirmEmail(username,newEmail).get();
    	}
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }					
	}

    
    @Override
    public void changeName(User user, String newTitle, String newFirstname, String newSurname) throws RaplaException 
    {
    	try
    	{
	    	RemoteStorage remoteMethod = getRemoteStorage();
	        String username = user.getUsername();
			remoteMethod.changeName(username,newTitle, newFirstname, newSurname).get();
	        refresh();
    	}
        catch (RaplaException ex)
        {
        	throw ex;
        }
        catch (Exception ex)
        {
        	throw new RaplaException(ex);
        }		

    }
    
    public Map<String,Entity> getFromId(Collection<String> idSet, boolean throwEntityNotFound) throws RaplaException
    {
     	RemoteStorage serv = getRemoteStorage();
     	String[] array = idSet.toArray(new String[] {});
     	Map<String,Entity> result = new HashMap<String,Entity>();
     	try
     	{
			UpdateEvent entityList = serv.getEntityRecursive( array).get();
			refresh( entityList);
	    	Collection<Entity> storeObjects = entityList.getStoreObjects();
			for (Entity entity:storeObjects)
			{
				String id = entity.getId();
				if ( idSet.contains( id ))
				{
					result.put( id, entity);
				}
			}
     	} 
     	catch (EntityNotFoundException ex)
     	{
     		if ( throwEntityNotFound)
     		{
     			throw ex;
     		}
		}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
		return result;
    }
   
    @Override
    public Entity tryResolve(String id) {
    	Assert.notNull( id);
    	Entity entity =  super.tryResolve(id);
    	if ( entity != null)
    	{
    		return entity;
    	}
    	if ( id.startsWith(Allocatable.TYPE.getLocalName()))
		{
			AllocatableImpl unresolved = new AllocatableImpl(null, null);
			unresolved.setId( id);
			unresolved.setClassification( getDynamicType(UNRESOLVED_RESOURCE_TYPE).newClassification());
			return unresolved;
		}
		// if the type is not found we test if its an anonymous type (key = 0)
		if ( id.startsWith(DynamicType.TYPE.getLocalName() + "_0"))
		{
			DynamicType unresolvedReservation = getDynamicType(ANONYMOUSEVENT_TYPE);
			return (Entity) unresolvedReservation;
		}
		return null;
    }
    
    public List<Reservation> getReservations(User user,Collection<Allocatable> allocatables,Date start,Date end,ClassificationFilter[] filters, Map<String,String> annotationQuery) throws RaplaException {
    	RemoteStorage serv = getRemoteStorage();
    	// if a refresh is due, we assume the system went to sleep so we refresh before we continue
    	if ( intervalLength > 0 && lastSyncedTime != null && (lastSyncedTime.getTime() + intervalLength * 2) < getCurrentTimestamp().getTime())
    	{
    	    getLogger().info("cache not uptodate. Refreshing first.");
    	    refresh();
    	}
    	
    	String[] allocatableId = getIdList(allocatables);
    	try
    	{
			List<ReservationImpl> list =serv.getReservations(allocatableId,start, end, annotationQuery).get();
	        Lock lock = readLock();
			try 
	        {
	        	testResolve( list);
	        	setResolver( list );
	        } 
			finally
	        {
				unlock(lock);
	        }
	        List<Reservation> result = new ArrayList<Reservation>();
	        Iterator it = list.iterator();
	        while ( it.hasNext())
	        {
	        	Object object = it.next();
	        	Reservation next = (Reservation)object;
	        	result.add( next);
	        }
	        removeFilteredClassifications(result, filters);
	        return result;
    	}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
    }
    
    public List<String> getTemplateNames() throws RaplaException {
    	checkConnected();
    	RemoteStorage serv = getRemoteStorage();
    	try
    	{
    		List<String> result = serv.getTemplateNames().get();
    		return result;
    	}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
    }

	protected String[] getIdList(Collection<? extends Entity> entities) {
		List<String> idList = new ArrayList<String>();
    	if ( entities != null )
    	{
    		for ( Entity entity:entities)
    		{
                if (entity != null)
    			    idList.add( ((Entity)entity).getId().toString());
    		}
    	}
    	String[] ids = idList.toArray(new String[] {});
		return ids;
	}
   
    synchronized private void refresh(UpdateEvent evt) throws RaplaException
    {
    	updateTimestamps(evt);		
    	if ( evt.isNeedResourcesRefresh())
    	{
    		refreshAll();
    		return;
    	}
    	UpdateResult result = null;
    	testResolve(evt.getStoreObjects());
    	setResolver(evt.getStoreObjects());
    	// we don't test the references of the removed objects
    	setResolver(evt.getRemoveObjects());
    	if ( bSessionActive  &&   !evt.isEmpty()  ) 
    	{
    	    getLogger().debug("Objects updated!");
    	    Lock writeLock = writeLock();
    	    try
    	    {
    	        result = update(evt);
    	    }
    	    finally
    	    {
    	        unlock(writeLock);
    	    }
        }
	
		if ( result != null && !result.isEmpty())
        {
            fireStorageUpdated(result);
        }
    }

	protected void refreshAll() throws RaplaException,EntityNotFoundException {
		UpdateResult result;
		Set<Entity> oldEntities; 
		Lock readLock = readLock();
        try
        {
            oldEntities = cache.getAllEntities();
        }
        finally
        {
            unlock(readLock);
        }
        Lock writeLock = writeLock();
		try
		{
		    loadData(null);
		}
		finally
		{
		    unlock(writeLock);
		}
		Set<Entity> newEntities; 
		readLock = readLock();
		try
		{
		    newEntities = cache.getAllEntities();
		}
		finally
		{
		    unlock(readLock);
		}
		HashSet<Entity> updated = new HashSet<Entity>(newEntities);
		Set<Entity> toRemove = new HashSet<Entity>(oldEntities);
		Set<Entity> toUpdate = new HashSet<Entity>(oldEntities);
		toRemove.removeAll(newEntities);
		updated.removeAll( toRemove);
		toUpdate.retainAll(newEntities);
		
		HashMap<Entity,Entity> oldEntityMap = new HashMap<Entity,Entity>();
		for ( Entity update: toUpdate)
		{
			Entity newEntity = cache.tryResolve( update.getId());
			if ( newEntity != null)
			{
				oldEntityMap.put( newEntity, update);
			}
		}
		TimeInterval invalidateInterval = new TimeInterval( null,null);
		result  = createUpdateResult(oldEntityMap, updated, toRemove, invalidateInterval, userId);
	
		fireStorageUpdated(result);
	}
    
    @Override
    protected void increaseVersion(Entity e) {
    	// Do nothing here versions are increased on the server
    }
    
    /**
	 * @param entity  
	 */
	protected boolean isAddedToUpdateResult(Entity entity) {
		RaplaType raplaType = entity.getRaplaType();
		if ((raplaType ==  Appointment.TYPE  || raplaType == Reservation.TYPE) )
		{
			return false;
		}
		return true;
	}

	/**
	 * @param entity  
	 */
	protected boolean isStorableInCache(Entity entity) {
		RaplaType raplaType = entity.getRaplaType();
		if  (raplaType == Conflict.TYPE)
		{
			return false;
		}
		else if ((raplaType ==  Appointment.TYPE  || raplaType == Reservation.TYPE) )
		{
			return false;
		}
		return true;
	}

	@Override
	public Map<Allocatable, Collection<Appointment>> getFirstAllocatableBindings( Collection<Allocatable> allocatables,	Collection<Appointment> appointments, Collection<Reservation> ignoreList) throws RaplaException {
		checkConnected();
    	RemoteStorage serv = getRemoteStorage();
    	String[] allocatableIds = getIdList(allocatables);
		//AppointmentImpl[] appointmentArray = appointments.toArray( new AppointmentImpl[appointments.size()]);
		String[] reservationIds = getIdList(ignoreList);
		List<AppointmentImpl> appointmentList = new ArrayList<AppointmentImpl>();
		Map<String,Appointment> appointmentMap= new HashMap<String,Appointment>();
		for ( Appointment app: appointments)
		{
			appointmentList.add( (AppointmentImpl) app);
			appointmentMap.put( app.getId(), app);
		}
		try
		{
			Map<String, List<String>> resultMap = serv.getFirstAllocatableBindings(allocatableIds, appointmentList, reservationIds).get().get();
			HashMap<Allocatable, Collection<Appointment>> result = new HashMap<Allocatable, Collection<Appointment>>();
			for ( Allocatable alloc:allocatables)
			{
				List<String> list = resultMap.get( alloc.getId());
				if ( list != null)
				{
					Collection<Appointment> appointmentBinding = new ArrayList<Appointment>();
					for ( String id:list)
					{
						Appointment e = appointmentMap.get( id);
						if ( e != null)
						{
							appointmentBinding.add( e);
						}
					}
					result.put( alloc, appointmentBinding);
				}
			}
			return result;
		}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }				
	}

	@Override
	public Map<Allocatable, Map<Appointment, Collection<Appointment>>> getAllAllocatableBindings( Collection<Allocatable> allocatables,	Collection<Appointment> appointments, Collection<Reservation> ignoreList) throws RaplaException {
		checkConnected();
    	RemoteStorage serv = getRemoteStorage();
    	String[] allocatableIds = getIdList(allocatables);
		List<AppointmentImpl> appointmentArray = Arrays.asList(appointments.toArray( new AppointmentImpl[]{}));
		String[] reservationIds = getIdList(ignoreList);
		List<ReservationImpl> serverResult;
		try
		{
			serverResult = serv.getAllAllocatableBindings(allocatableIds, appointmentArray, reservationIds).get();
		}
		catch (RaplaException ex)
		{
			throw ex;
		}
		catch (Exception ex)
		{
			throw new RaplaException(ex);
		}
		testResolve( serverResult);
	    setResolver( serverResult );
        SortedSet<Appointment> allAppointments = new TreeSet<Appointment>(new AppointmentStartComparator());
        for ( ReservationImpl reservation: serverResult)
        {
        	allAppointments.addAll(reservation.getAppointmentList());
        }
		Map<Allocatable, Map<Appointment,Collection<Appointment>>> result = new HashMap<Allocatable, Map<Appointment,Collection<Appointment>>>();
		for ( Allocatable alloc:allocatables)
		{
			Map<Appointment,Collection<Appointment>> appointmentBinding = new HashMap<Appointment, Collection<Appointment>>();
			for (Appointment appointment: appointments)
			{
				SortedSet<Appointment> appointmentSet = getAppointments(alloc, allAppointments );
				boolean onlyFirstConflictingAppointment = false;
				Set<Appointment> conflictingAppointments = AppointmentImpl.getConflictingAppointments(appointmentSet, appointment, ignoreList, onlyFirstConflictingAppointment);
				appointmentBinding.put( appointment, conflictingAppointments);
			}
			result.put( alloc, appointmentBinding);
		}
		return result;
	}
	
	@Override
	public Date getNextAllocatableDate(Collection<Allocatable> allocatables,Appointment appointment, Collection<Reservation> ignoreList, Integer worktimeStartMinutes,Integer worktimeEndMinutes, Integer[] excludedDays, Integer rowsPerHour) throws RaplaException
	{
		checkConnected();
    	RemoteStorage serv = getRemoteStorage();
    	String[] allocatableIds = getIdList(allocatables);
		String[] reservationIds = getIdList(ignoreList);
		try
		{
			Date result = serv.getNextAllocatableDate(allocatableIds, (AppointmentImpl)appointment, reservationIds, worktimeStartMinutes, worktimeEndMinutes, excludedDays, rowsPerHour).get();
			return result;
		}
	    catch (RaplaException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	throw new RaplaException(ex);
	    }		
	}


	static private SortedSet<Appointment> getAppointments(Allocatable alloc, SortedSet<Appointment> allAppointments) 
	{
		SortedSet<Appointment> result = new TreeSet<Appointment>(new AppointmentStartComparator());
        for ( Appointment appointment:allAppointments)
        {
        	Reservation reservation = appointment.getReservation();
        	if ( reservation.hasAllocated( alloc, appointment))
        	{
        		result.add( appointment);
        	}
        }
		return result;
	}

	@Override
	public Collection<Conflict> getConflicts(User user) throws RaplaException {
        checkConnected();
    	RemoteStorage serv = getRemoteStorage();
    	try
    	{
	    	List<ConflictImpl> list = serv.getConflicts().get();
	    	testResolve( list);
	    	setResolver( list);
	        List<Conflict> result = new ArrayList<Conflict>();
	        Iterator it = list.iterator();
	        while ( it.hasNext())
	        {
	        	Object object = it.next();
	        	if ( object instanceof Conflict)
	        	{
	        		Conflict next = (Conflict)object;
	        		result.add( next);
	        	}
	        }
	        return result;
    	}
    	catch (RaplaException ex)
    	{
    		throw ex;
    	}
    	catch (Exception ex)
    	{
    		throw new RaplaException(ex);
    	}		
	}


//	@Override
//	protected void logEntityNotFound(Entity obj, EntityNotFoundException ex) {
//		RemoteStorage serv = getRemoteStorage();
//		Comparable id = ex.getId();
//		try {
//			if ( obj instanceof ConflictImpl)
//			{
//				Iterable<String> referencedIds = ((ConflictImpl)obj).getReferencedIds();
//				List<String> ids = new ArrayList<String>();
//				for (String refId:referencedIds)
//				{
//					ids.add(  refId);
//				}
//				serv.logEntityNotFound( id  + " not found in conflict :",ids.toArray(new  String[0]));
//			}
//			else if ( id != null )
//			{
//				serv.logEntityNotFound("Not found", id.toString() );
//			}
//		} catch (Exception e) {
//			getLogger().error("Can't call server logging for " + ex.getMessage() + " due to " + e.getMessage(), e);
//		}
//	}
}

