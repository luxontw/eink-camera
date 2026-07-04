package tw.newxe.einkcamera.eis;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.util.Log;

import java.util.TreeMap;

/**
 * Manages Gyroscope data and computes stabilization matrices.
 */
public class EisManager implements SensorEventListener {
    private static final String TAG = "EisManager";
    private static final int GYRO_BUFFER_SIZE_NS = 1000_000_000; // 1 second buffer

    private final SensorManager mSensorManager;
    private final Sensor mGyroscope;
    
    // Buffer for gyro events: timestamp (ns) -> angular velocities [x, y, z]
    private final TreeMap<Long, float[]> mGyroBuffer = new TreeMap<>();
    
    private final float[] mCurrentRotation = new float[16];
    private final float[] mSmoothedRotation = new float[16];
    private long mLastGyroTimestamp = 0;
    
    private float mSmoothingFactor = 0.95f; // Alpha for low-pass filter

    public EisManager(Context context) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        
        Matrix.setIdentityM(mCurrentRotation, 0);
        Matrix.setIdentityM(mSmoothedRotation, 0);
    }

    public void start() {
        if (mGyroscope != null) {
            mSensorManager.registerListener(this, mGyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    public void stop() {
        mSensorManager.unregisterListener(this);
        synchronized (mGyroBuffer) {
            mGyroBuffer.clear();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            synchronized (mGyroBuffer) {
                mGyroBuffer.put(event.timestamp, event.values.clone());
                
                // Evict old data
                long threshold = event.timestamp - GYRO_BUFFER_SIZE_NS;
                while (!mGyroBuffer.isEmpty() && mGyroBuffer.firstKey() < threshold) {
                    mGyroBuffer.pollFirstEntry();
                }
            }
            
            updateOrientation(event.timestamp, event.values);
        }
    }

    private void updateOrientation(long timestamp, float[] gyroValues) {
        if (mLastGyroTimestamp != 0) {
            float dt = (timestamp - mLastGyroTimestamp) * 1e-9f;
            
            // Basic integration of angular velocity
            float axisX = gyroValues[0];
            float axisY = gyroValues[1];
            float axisZ = gyroValues[2];
            float angle = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ) * dt;
            
            if (angle > 1e-6f) {
                float[] deltaRotation = new float[16];
                Matrix.setRotateM(deltaRotation, 0, (float) Math.toDegrees(angle), axisX, axisY, axisZ);
                
                float[] temp = new float[16];
                Matrix.multiplyMM(temp, 0, mCurrentRotation, 0, deltaRotation, 0);
                System.arraycopy(temp, 0, mCurrentRotation, 0, 16);
            }
        }
        mLastGyroTimestamp = timestamp;

        // Apply Low-Pass Filter for smoothed path
        for (int i = 0; i < 16; i++) {
            mSmoothedRotation[i] = mSmoothingFactor * mSmoothedRotation[i] + (1 - mSmoothingFactor) * mCurrentRotation[i];
        }
        // Normalize (optional for simple LPF on matrix, but good for stability)
        // In a production environment, Quaternions are better for interpolation/smoothing.
    }

    /**
     * Calculates the stabilization matrix for a given timestamp.
     * @param timestampNs The frame timestamp in nanoseconds.
     * @param width Frame width.
     * @param height Frame height.
     * @return A 4x4 matrix to be used in OpenGL (MVP matrix style).
     */
    public float[] getStabilizationMatrix(long timestampNs, int width, int height) {
        // In this lightweight implementation, we use the latest computed difference.
        // R_comp = R_current * R_smoothed^T
        float[] smoothedT = new float[16];
        Matrix.transposeM(smoothedT, 0, mSmoothedRotation, 0);
        
        float[] compensation = new float[16];
        Matrix.multiplyMM(compensation, 0, smoothedT, 0, mCurrentRotation, 0);
        
        // Apply a zoom factor (e.g., 1.1x) to hide edges
        float zoom = 1.1f;
        Matrix.scaleM(compensation, 0, zoom, zoom, 1.0f);
        
        return compensation;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
