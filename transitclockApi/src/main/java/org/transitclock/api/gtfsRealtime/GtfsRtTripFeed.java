/*
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */

package org.transitclock.api.gtfsRealtime;

import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitclock.api.data.IpcPredictionComparator;
import org.transitclock.api.utils.AgencyTimezoneCache;
import org.transitclock.config.BooleanConfigValue;
import org.transitclock.config.IntegerConfigValue;
import org.transitclock.core.holdingmethod.PredictionTimeComparator;
import org.transitclock.ipc.clients.PredictionsInterfaceFactory;
import org.transitclock.ipc.data.IpcPrediction;
import org.transitclock.ipc.data.IpcPredictionsForRouteStopDest;
import org.transitclock.utils.IntervalTimer;
import org.transitclock.utils.Time;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.FeedHeader.Incrementality;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
 
/**
 * For creating GTFS-realtime trip feed. The data is obtained from the server
 * via RMI.
 * <p>
 * Note: for the trip feed predictions that are schedule based instead of GPS
 * based the StopTimeEvent uncertainty is set to
 * SCHED_BASED_PRED_UNCERTAINTY_VALUE so that the client can treat the
 * prediction differently. If a vehicle is delayed and not moving then
 * uncertainty is set to DELAYED_UNCERTAINTY_VALUE. And if a vehicle is late and
 * the prediction is for a subsequent trip then uncertainty is set to
 * LATE_AND_SUBSEQUENT_TRIP_UNCERTAINTY_VALUE.
 * 
 * @author SkiBu Smith
 *
 */
public class GtfsRtTripFeed {

	private final String agencyId;
	
	// For outputting date in GTFS-realtime format
	private SimpleDateFormat gtfsRealtimeDateFormatter = 
			new SimpleDateFormat("yyyyMMdd");
	
	private SimpleDateFormat gtfsRealtimeTimeFormatter = 
			new SimpleDateFormat("HH:mm:ss");
	
	
	private static IntegerConfigValue predictionMaxFutureSecs = new IntegerConfigValue(
			"transitclock.api.predictionMaxFutureSecs", 60 * 60,
			"Number of seconds in the future to accept predictions before");
	private static final int PREDICTION_MAX_FUTURE_SECS = predictionMaxFutureSecs.getValue();


	private static BooleanConfigValue includeTripUpdateDelay = new BooleanConfigValue(
			"transitclock.api.includeTripUpdateDelay", false,
			"Whether or not to include delay in the TripUpdate message");
	private static final boolean INCLUDE_TRIP_UPDATE_DELAY = includeTripUpdateDelay.getValue();
	
	
	// For when creating StopTimeEvent for schedule based prediction  
	// 5 minutes (300 seconds)
	private static final int SCHED_BASED_PRED_UNCERTAINTY_VALUE = 5 * 60; 
	
	// For when creating StopTimeEvent and the vehicle is delayed
	private static final int DELAYED_UNCERTAINTY_VALUE = 
			SCHED_BASED_PRED_UNCERTAINTY_VALUE + 1;
	
	// If vehicle is late and prediction is for a subsequent trip then
	// the predictions are not as certain because it is reasonably likely
	// that another vehicle will take over the subsequent trip. Takes
	// precedence over SCHED_BASED_PRED_UNCERTAINTY_VALUE.
	private static final int LATE_AND_SUBSEQUENT_TRIP_UNCERTAINTY_VALUE = 
			DELAYED_UNCERTAINTY_VALUE + 1;
	
	private static final Logger logger = 
			LoggerFactory.getLogger(GtfsRtTripFeed.class);

	/********************** Member Functions **************************/

	public GtfsRtTripFeed(String agencyId) {
		this.agencyId = agencyId;	
		
		this.gtfsRealtimeDateFormatter.setTimeZone(AgencyTimezoneCache
				.get(agencyId));
	}

	/**
	 * Create TripUpdate for the trip.
	 * 
	 * @param predsForTrip
	 * @return
	 */
	private TripUpdate createTripUpdate(List<IpcPrediction> predsForTrip) {
		// Create the parent TripUpdate object that is returned.
		TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();

		// Add the trip descriptor information
		IpcPrediction firstPred = predsForTrip.get(0);
		TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
		if (firstPred.getRouteId() != null)
			tripDescriptor.setRouteId(firstPred.getRouteId());
		if (firstPred.getTripId() != null) {
			tripDescriptor.setTripId(firstPred.getTripId());
			 			
			try {
				if(firstPred.getFreqStartTime()>0)
				{
					String tripStartTimeStr=gtfsRealtimeTimeFormatter.format(new Date(firstPred.getFreqStartTime()));
					tripDescriptor.setStartTime(tripStartTimeStr);
				}
			} catch (Exception e) {
				
			}
			
			long tripStartEpochTime = firstPred.getTripStartEpochTime();
			String tripStartDateStr =
					gtfsRealtimeDateFormatter.format(new Date(
							tripStartEpochTime));
			tripDescriptor.setStartDate(tripStartDateStr);

			// Set the relation between this trip and the static schedule. ADDED and CANCELED not supported. 
			if (firstPred.isTripUnscheduled()) {
				// A trip that is running with no schedule associated to it - 
				// this value is used to identify trips defined in GTFS frequencies.txt with exact_times = 0
				tripDescriptor.setScheduleRelationship(TripDescriptor.ScheduleRelationship.UNSCHEDULED);
			} else {
				// Trip that is running in accordance with its GTFS schedule, 
				// or is close enough to the scheduled trip to be associated with it.
				tripDescriptor.setScheduleRelationship(TripDescriptor.ScheduleRelationship.SCHEDULED);
			}
		}
	
		//Set trip as canceled if it is mark as that from schedBasePreds
		if(firstPred.isCanceled())
			tripDescriptor.setScheduleRelationship(com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED);
			
		tripUpdate.setTrip(tripDescriptor);
		if (firstPred.getDelay() != null && INCLUDE_TRIP_UPDATE_DELAY)
		  tripUpdate.setDelay(firstPred.getDelay()); // set schedule deviation

		// Add the VehicleDescriptor information
		VehicleDescriptor.Builder vehicleDescriptor =
				VehicleDescriptor.newBuilder().setId(firstPred.getVehicleId());
		tripUpdate.setVehicle(vehicleDescriptor);

		// Add the StopTimeUpdate information for each prediction
		if(!firstPred.isCanceled())
		{
			for (IpcPrediction pred : predsForTrip ) {
				StopTimeUpdate.Builder stopTimeUpdate =	StopTimeUpdate.newBuilder()
						.setStopSequence(pred.getGtfsStopSeq())
						.setStopId(pred.getStopId());

				StopTimeEvent.Builder stopTimeEvent = StopTimeEvent.newBuilder();
				stopTimeEvent.setTime(pred.getPredictionTime() / Time.MS_PER_SEC);

				// If schedule based prediction then set the uncertainty to special
				// value so that client can tell
				if (pred.isSchedBasedPred())
					stopTimeEvent.setUncertainty(SCHED_BASED_PRED_UNCERTAINTY_VALUE);

				// If vehicle is late and prediction is for a subsequent trip then
				// the predictions are not as certain because it is reasonably likely
				// that another vehicle will take over the subsequent trip. Takes
				// precedence over SCHED_BASED_PRED_UNCERTAINTY_VALUE.
				if (pred.isLateAndSubsequentTripSoMarkAsUncertain())
					stopTimeEvent.setUncertainty(LATE_AND_SUBSEQUENT_TRIP_UNCERTAINTY_VALUE);

				// If vehicle not making forward progress then set uncertainty to
				// special value so that client can tell. Takes precedence over 
				// LATE_AND_SUBSEQUENT_TRIP_UNCERTAINTY_VALUE.
				if (pred.isDelayed())
					stopTimeEvent.setUncertainty(DELAYED_UNCERTAINTY_VALUE);

				if (pred.isArrival())
					stopTimeUpdate.setArrival(stopTimeEvent);
				else
					stopTimeUpdate.setDeparture(stopTimeEvent);

				//The relationship should always be SCHEDULED if departure or arrival time is given.
				stopTimeUpdate.setScheduleRelationship(ScheduleRelationship.SCHEDULED);

				tripUpdate.addStopTimeUpdate(stopTimeUpdate);
			}
		}
		// Add timestamp
		tripUpdate.setTimestamp(firstPred.getAvlTime() / Time.MS_PER_SEC);
		
		// Return the results
		return tripUpdate.build();
	}
	
	/**
	 * Creates a GTFS-realtime message for the predictions by trip passed in.
	 * 
	 * @param predsByTripMap
	 *            the data to be put into the GTFS-realtime message
	 * @return the GTFS-realtime FeedMessage
	 */
	private FeedMessage createMessage(Map<String, List<IpcPrediction>> predsByTripMap) {
		FeedMessage.Builder message = FeedMessage.newBuilder();
		
		FeedHeader.Builder feedheader = FeedHeader.newBuilder()
				.setGtfsRealtimeVersion("1.0")
				.setIncrementality(Incrementality.FULL_DATASET)
				.setTimestamp(System.currentTimeMillis() / Time.MS_PER_SEC);
		message.setHeader(feedheader);
		//Create a comparator to sort each trip data
		Comparator<IpcPrediction> comparator=new IpcPredictionComparator();
		// For each trip...
		for (List<IpcPrediction> predsForTrip : predsByTripMap.values()) {
			//Sort trip data according to sequnece
			
			Collections.sort(predsForTrip, comparator);
			//  Need to check if predictions for frequency based trip and group by start time if they are. 
			if(isFrequencyBasedTrip(predsForTrip))
			{
				try {
					Map<Long, List<IpcPrediction>> map = createFreqStartTimePredictionMap(predsForTrip);
					
					for(Long key:map.keySet())
					{
						if(!map.get(key).isEmpty())
						{
							FeedEntity.Builder feedEntity = FeedEntity.newBuilder()
									.setId(map.get(key).get(0).getVehicleId());						
							TripUpdate tripUpdate = createTripUpdate(map.get(key));
							feedEntity.setTripUpdate(tripUpdate);		
							message.addEntity(feedEntity);
						}
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}							
			}else
			{			
				// Create feed entity for each schedule trip
				FeedEntity.Builder feedEntity = FeedEntity.newBuilder()
						.setId(predsForTrip.get(0).getTripId());
				try {				
					TripUpdate tripUpdate = createTripUpdate(predsForTrip);
					feedEntity.setTripUpdate(tripUpdate);		
		    		message.addEntity(feedEntity);
				} catch (Exception e) {
					logger.error("Error parsing trip update data. {}",
							predsForTrip, e);
				}
			}
		}		
		
		return message.build();
	}
	private boolean isFrequencyBasedTrip(List<IpcPrediction> predsForTrip)		
	{
		for(IpcPrediction prediction:predsForTrip) 
		{		
				if(prediction.getFreqStartTime() > 0)
					return true;		
		}
		return false;		
	}
	private Map<Long, List<IpcPrediction>> createFreqStartTimePredictionMap(List<IpcPrediction> predsForTrip)
	{
		Map<Long, List<IpcPrediction>> map=new HashMap<>();
		for(IpcPrediction prediction:predsForTrip)
		{
			if(map.get(prediction.getFreqStartTime())==null)
			{
				List<IpcPrediction> list=new ArrayList<IpcPrediction>(); 
				map.put(prediction.getFreqStartTime(), list);
								
			}			
			map.get(prediction.getFreqStartTime()).add(prediction);	
			
			Collections.sort(map.get(prediction.getFreqStartTime()), new PredictionTimeComparator());
		}
		return map;		
	}
	/**
	 * Returns map of all predictions for the project. Returns null if there was
	 * a problem getting the data via RMI. There is a separate list of
	 * predictions for each trip. The map is keyed by tripId.
	 * 
	 * @return Map keyed on tripId of List of Predictions for the trip, or null
	 *         if could not get data from server.
	 */
	private Map<String, List<IpcPrediction>> getPredictionsPerTrip() {
		// Get all the predictions, grouped by vehicle, from the server
		List<IpcPredictionsForRouteStopDest> allPredictionsByStop;
		try {
			allPredictionsByStop = PredictionsInterfaceFactory.get(agencyId)
					.getAllPredictions(PREDICTION_MAX_FUTURE_SECS);
		} catch (RemoteException e) {
			logger.error("Exception when getting vehicles from RMI", e);
			return null;
		}
		
		// Group the predictions by trip instead of by vehicle
		Map<String, List<IpcPrediction>> predictionsByTrip = 
				new HashMap<String, List<IpcPrediction>>();
		for (IpcPredictionsForRouteStopDest predictionsForStop : 
				allPredictionsByStop) {
			for (IpcPrediction prediction : 
					predictionsForStop.getPredictionsForRouteStop()) {
				String tripId = prediction.getTripId();
				List<IpcPrediction> predsForTrip = predictionsByTrip.get(tripId);
				if (predsForTrip == null) {
					// A new trip so need to use a new trip list
					predsForTrip = new ArrayList<IpcPrediction>();
					predictionsByTrip.put(tripId, predsForTrip);					
				}
				
				predsForTrip.add(prediction);
			}
		}
		
		// Return results
		return predictionsByTrip;
	}

	/**
	 * Gets the Vehicle data from RMI and creates corresponding
	 * GTFS-RT vehicle feed.
	 * 
	 * @return GTFS-RT FeedMessage for vehicle positions
	 */
	public FeedMessage createMessage() {
		// Get prediction data from server
		IntervalTimer timer = new IntervalTimer();
		Map<String, List<IpcPrediction>> predsByTrip = getPredictionsPerTrip();
		logger.debug("Getting predictions via RMI for " +
				"GtfsRtTripFeed.createMessage() took {} msec", 
				timer.elapsedMsec());
		
		// Use prediction data to create GTFS-RT message and return it.
		return createMessage(predsByTrip);
	}

	// For getPossiblyCachedMessage()
	private static final DataCache tripFeedDataCache = new DataCache();
	
	/**
	 * For caching Vehicle Positions feed messages.
	 * 
	 * @param agencyId
	 * @param cacheTime
	 * @return
	 */
	public static FeedMessage getPossiblyCachedMessage(String agencyId, int cacheTime) {
	    FeedMessage feedMessage = tripFeedDataCache.get(agencyId, cacheTime);
	    if (feedMessage != null)
	    	return feedMessage;
	    
	    synchronized(tripFeedDataCache) {
	    	
	    	// Cache may have been filled while waiting.
	    	feedMessage = tripFeedDataCache.get(agencyId, cacheTime);
	    	if (feedMessage != null)
	    		return feedMessage;
	    	
	    	GtfsRtTripFeed feed = new GtfsRtTripFeed(agencyId);
		    feedMessage = feed.createMessage();
		    tripFeedDataCache.put(agencyId, feedMessage);
	    }
	    
	    return feedMessage;
	}

}
