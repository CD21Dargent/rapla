package org.rapla.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.inject.Inject;

import org.rapla.client.event.PlaceChangedEvent;
import org.rapla.client.event.PlaceChangedEvent.PlaceChangedEventHandler;
import org.rapla.client.event.StartActivityEvent;
import org.rapla.client.event.StartActivityEvent.StartActivityEventHandler;
import org.rapla.client.event.StopActivityEvent;
import org.rapla.client.event.StopActivityEvent.StopActivityEventHandler;
import org.rapla.framework.RaplaException;
import org.rapla.framework.logger.Logger;

import com.google.web.bindery.event.shared.EventBus;

public abstract class ActivityManager
        implements PlaceChangedEventHandler, StartActivityEventHandler, StopActivityEventHandler
{

    private final Application<?> application;
    protected Place place;
    protected final Set<Activity> activities = new LinkedHashSet<Activity>();
    protected final Logger logger;

    @Inject
    public ActivityManager(@SuppressWarnings("rawtypes") Application application, EventBus eventBus, Logger logger)
    {
        this.application = application;
        this.logger = logger;
        eventBus.addHandler(PlaceChangedEvent.TYPE, this);
        eventBus.addHandler(StartActivityEvent.TYPE, this);
        eventBus.addHandler(StopActivityEvent.TYPE, this);
    }

    @Override
    public void startActivity(StartActivityEvent event)
    {
        Activity activity = new Activity(event.getName(), event.getId());
        activities.add(activity);
        updateHistroryEntry();
        application.startActivity(activity);
    }

    @Override
    public void stopActivity(StopActivityEvent event)
    {
        Activity activity = new Activity(event.getName(), event.getId());
        activities.remove(activity);
        updateHistroryEntry();
    }

    @Override
    public void placeChanged(PlaceChangedEvent event)
    {
        place = event.getNewPlace();
        updateHistroryEntry();
        application.selectPlace(place);
    }

    public final void init() throws RaplaException
    {
        parsePlaceAndActivities();
        application.selectPlace(place);
        if (!activities.isEmpty())
        {
            ArrayList<Activity> toRemove = new ArrayList<Activity>();
            for (Activity activity : activities)
            {
                if(!application.startActivity(activity))
                {
                    toRemove.add(activity);
                }
            }
            if(!toRemove.isEmpty())
            {
                activities.removeAll(toRemove);
                updateHistroryEntry();
            }
        }
    }

    protected abstract void parsePlaceAndActivities() throws RaplaException;

    protected abstract void updateHistroryEntry();

    private static final String ACTIVITY_SEPARATOR = "=";

    public static class Activity
    {
        private final String name;
        private final String id;

        public Activity(String name, String id)
        {
            this.name = name;
            this.id = id;
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }

        @Override
        public String toString()
        {
            return name + "=" + id;
        }

        public static Activity fromString(final String activityString)
        {
            if (activityString == null)
            {
                return null;
            }
            int indexOf = activityString.indexOf(ACTIVITY_SEPARATOR);
            if (indexOf > 0)
            {
                String name = activityString.substring(0, indexOf);
                String id = activityString.substring(indexOf + 1);
                return new Activity(name, id);
            }
            return null;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((id == null) ? 0 : id.hashCode());
            result = prime * result + ((name == null) ? 0 : name.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Activity other = (Activity) obj;
            if (id == null)
            {
                if (other.id != null)
                    return false;
            }
            else if (!id.equals(other.id))
                return false;
            if (name == null)
            {
                if (other.name != null)
                    return false;
            }
            else if (!name.equals(other.name))
                return false;
            return true;
        }

    }

    private static final String PLACE_SEPARATOR = "/";

    public static class Place
    {
        private final String name;
        private final String id;

        public Place(String name, String id)
        {
            this.name = name;
            this.id = id;
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder(name);
            if (id != null)
            {
                sb.append(PLACE_SEPARATOR);
                sb.append(id);
            }
            return sb.toString();
        }

        public static Place fromString(String token)
        {
            if (token == null || token.isEmpty())
            {
                return null;
            }
            String substring = token;
            int paramsIndex = substring.indexOf("?");
            if (paramsIndex == 0)
            {
                return null;
            }
            if (paramsIndex > 0)
            {
                substring = substring.substring(0, paramsIndex);
            }
            int separator = substring.indexOf(PLACE_SEPARATOR);
            final String name;
            final String id;
            if (separator >= 0)
            {
                name = substring.substring(0, separator);
                id = substring.substring(separator + 1);
            }
            else
            {
                name = substring;
                id = null;
            }
            return new Place(name, id);
        }
    }

}