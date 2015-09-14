package org.rapla.client.gwt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.rapla.client.ActivityManager;
import org.rapla.client.Application;
import org.rapla.framework.RaplaException;
import org.rapla.framework.logger.Logger;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.user.client.History;
import com.google.web.bindery.event.shared.EventBus;
import org.rapla.inject.DefaultImplementation;
import org.rapla.inject.InjectionContext;

@DefaultImplementation(of=ActivityManager.class, context = InjectionContext.gwt)
public class GwtActivityManagerImpl extends ActivityManager
{
    @Inject
    public GwtActivityManagerImpl(Application application, EventBus eventBus, Logger logger)
    {
        super(application, eventBus, logger);
        History.addValueChangeHandler(new ValueChangeHandler<String>()
        {
            @Override
            public void onValueChange(ValueChangeEvent<String> event)
            {
                try
                {
                    GwtActivityManagerImpl.this.init();
                }
                catch (RaplaException e)
                {
                    logger.error("Error updating history change: " + e.getMessage(), e);
                }
            }
        });
    }

    @Override
    protected void parsePlaceAndActivities() throws RaplaException
    {
        // theory, this class is loaded on startup, so check the url and fire
        // events
        final String token = History.getToken();
        activities.clear();
        place = null;
        if (token != null && !token.isEmpty())
        {
            place = Place.fromString(token);
            int activitiesStartIndex = token.indexOf("?");
            final String activitiesString = activitiesStartIndex >= 0 ? token.substring(activitiesStartIndex + 1) : null;
            if (activitiesString != null)
            {
                String[] activitiesAsStringList = activitiesString.split("&");
                for (String activityListAsString : activitiesAsStringList)
                {
                    final String[] split = activityListAsString.split("=");
                    final String name = split[0];
                    final String[] activitiyIds = split[1].split(",");
                    for (String activityId : activitiyIds)
                    {
                        final Activity activity = new Activity(name, activityId);
                        activities.add(activity);
                    }
                }
            }
        }
    }

    protected void updateHistroryEntry()
    {
        final StringBuilder sb = new StringBuilder();
        if (place != null)
        {
            sb.append(place.toString());
        }
        if (!activities.isEmpty())
        {
            Map<String, List<Activity>> activitiesMap = new LinkedHashMap<String, List<Activity>>();
            sb.append("?");
            for (Iterator<Activity> iterator = activities.iterator(); iterator.hasNext();)
            {
                final Activity activity = iterator.next();
                List<Activity> activitiesList = activitiesMap.get(activity.getName());
                if(activitiesList == null)
                {
                    activitiesList = new ArrayList<Activity>();
                    activitiesMap.put(activity.getName(), activitiesList);
                }
                activitiesList.add(activity);
            }
            for (Entry<String, List<Activity>> entries : activitiesMap.entrySet())
            {
                final String name = entries.getKey();
                sb.append(name);
                sb.append("=");
                final List<Activity> activitiesList = entries.getValue();
                for (Activity activity : activitiesList)
                {
                    sb.append(activity.getId());
                    sb.append(",");
                }
                // delete last ','
                sb.deleteCharAt(sb.length()-1);
                sb.append("&");
            }
            // delete last &
            sb.deleteCharAt(sb.length()-1);
        }
        History.newItem(sb.toString(), false);
    }

}