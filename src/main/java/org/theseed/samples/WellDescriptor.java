/**
 *
 */
package org.theseed.samples;

import org.apache.commons.lang3.StringUtils;

/**
 * A well descriptor contains the information needed to identify the specific location and time point of
 * a result.  This includes the plate ID, the well address (row letter / column number), and the time
 * point value.  In the master file, the time point appears in the "time" column, the plate ID in the
 * "experiment" column, and the well address in the "Sample_y" column.
 *
 * @author Bruce Parrello
 *
 */
public class WellDescriptor {

    // FIELDS
    /** plate ID */
    private String plate;
    /** well address */
    private String well;
    /** time point */
    private double timePoint;

    /**
     * Construct a well descriptor.
     *
     * @param plateId		plate identifier
     * @param wellAddress	well address (row letter / column number)
     * @param time			time point in hours
     */
    public WellDescriptor(String plateId, String wellAddress, double time) {
        this.plate = plateId;
        this.well = wellAddress;
        this.timePoint = time;
    }

    /**
     * Create a well descriptor from a formatted well/plate ID and a time point.
     *
     * @param origin	formatted well/plate ID (plate:well)
     * @param time		relevant time point
     */
    public WellDescriptor(String origin, double time) {
        this.plate = StringUtils.substringBefore(origin, ":");
        this.well = StringUtils.substringAfter(origin, ":");
        this.timePoint = time;
    }

    /**
     * @return the time point
     */
    public double getTime() {
        return this.timePoint;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.plate == null) ? 0 : this.plate.hashCode());
        long temp;
        temp = Double.doubleToLongBits(this.timePoint);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        result = prime * result + ((this.well == null) ? 0 : this.well.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WellDescriptor)) {
            return false;
        }
        WellDescriptor other = (WellDescriptor) obj;
        if (this.plate == null) {
            if (other.plate != null) {
                return false;
            }
        } else if (!this.plate.equals(other.plate)) {
            return false;
        }
        if (Double.doubleToLongBits(this.timePoint) != Double.doubleToLongBits(other.timePoint)) {
            return false;
        }
        if (this.well == null) {
            if (other.well != null) {
                return false;
            }
        } else if (!this.well.equals(other.well)) {
            return false;
        }
        return true;
    }

    /**
     * @return the well identifier for this descriptor
     */
    public String getOrigin() {
        return this.plate + ":" + this.well;
    }

}
