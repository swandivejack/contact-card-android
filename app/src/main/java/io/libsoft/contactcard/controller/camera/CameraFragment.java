/*****************************************************
 * This work is Copyright, 2019, Isaac Lindland      *
 * All rights reserved.                              *
 *****************************************************/

package io.libsoft.contactcard.controller.camera;


import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureRequest.Builder;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;
import io.libsoft.contactcard.R;
import io.libsoft.contactcard.model.pojo.Cropper;
import io.libsoft.contactcard.service.FileManagerService;
import io.libsoft.contactcard.service.ImageProcessingService;
import io.libsoft.contactcard.viewmodel.MainViewModel;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opencv.core.Size;


public class CameraFragment extends Fragment {


  private static final int PERMISSION_GRANTED = PackageManager.PERMISSION_GRANTED;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private final String TAG = "CameraFragment";
  private Context context;
  private View view;
  private TextureView textureView;
  private ImageView processedView;
  private CameraManager manager;
  private CameraDevice cameraDevice;
  private String cameraId;
  private Size imageDimensions;
  private CameraStateCallback stateCallback;
  private Builder captureRequestBuilder;
  private CameraCaptureSessionStateCallback sessionStateCallback;
  private CameraCaptureSession cameraCaptureSession;
  private Handler mBackgroundHandler;
  private HandlerThread mBackgroundThread;
  private CameraCaptureListener captureListener;
  private CameraSurfaceTextureListener surfaceTextureListener;
  private Surface surface;
  private CameraCharacteristics characteristics;
  private FileManagerService fileManagerService;
  private ImageReader reader;
  private MainViewModel viewModel;
  private Bitmap latestBmp;
  private boolean captureAvailable;


  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {
    context = inflater.getContext();
    view = inflater.inflate(R.layout.fragment_camera, container, false);

    fileManagerService = FileManagerService.getInstance();
    manager = ((CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE));

    initViews();
    initViewModel();
    initListeners();
    return view;
  }

  private void initViewModel() {
    viewModel = ViewModelProviders.of(this).get(MainViewModel.class);
    getLifecycle().addObserver(viewModel);
  }

  private void initViews() {
    textureView = view.findViewById(R.id.camera_preview);
    processedView = view.findViewById(R.id.processed_image);
  }

  @SuppressLint("ClickableViewAccessibility")
  private void initListeners() {
    surfaceTextureListener = new CameraSurfaceTextureListener()
        .setOnComplete(this::openCamera)
        .setTextureUpdatedListener((surfaceTexture) -> {
        });
    textureView.setSurfaceTextureListener(surfaceTextureListener);
    stateCallback = new CameraStateCallback((cameraDevice) -> {
      Log.d(TAG, "opened");
      this.cameraDevice = cameraDevice;
      createCameraPreview();
    }, (cameraDevice) -> {
      closeCamera();
      this.cameraDevice = null;
    });

    sessionStateCallback = new CameraCaptureSessionStateCallback();
    sessionStateCallback.setOnConfigured((session) -> {
      if (cameraDevice != null) {
        cameraCaptureSession = session;
        updatePreview();
      }
    });

    captureListener = new CameraCaptureListener().setOnCompleted(() -> {
      latestBmp = textureView.getBitmap();
      Cropper rectangle = ImageProcessingService.getInstance().findCard(latestBmp, imageDimensions);
      Objects.requireNonNull(getActivity()).runOnUiThread(() -> {
        processedView.setImageBitmap(rectangle.getOriginal());
      });
    });

    ImageProcessingService.getInstance().getCandidates().observe(this, (rects) -> {

      if (rects.size() > 10 && captureAvailable) {
        executor.submit(this::captureAndPreview);
        rects.clear();
        captureAvailable = false;
      }
    });

    captureAvailable = true;

    processedView.setOnClickListener((view) -> {
      ImageProcessingService.getInstance().clear();
      captureAvailable = true;
    });
  }

  private void captureAndPreview() {
    Bitmap bmp = ImageProcessingService.getInstance().getCroppedImage(latestBmp);
    Bundle bundle = new Bundle();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bmp.compress(Bitmap.CompressFormat.PNG, 100, stream);
    byte[] byteArray = stream.toByteArray();
    bundle.putByteArray("image", byteArray);
    ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(200);
    Navigation.findNavController(view)
        .navigate(R.id.action_cameraFragment_to_imageReviewFragment, bundle);

  }


  private void updatePreview() {
    Log.d(TAG, "updatePreview: start");
    if (cameraDevice != null) {
      captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
      try {
        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), captureListener,
            mBackgroundHandler);
      } catch (CameraAccessException e) {
        e.printStackTrace();
      }
    }
    Log.d(TAG, "updatePreview: end");
  }

  private void createCameraPreview() {
    Log.d(TAG, "createCameraPreview: start");
    textureView.getSurfaceTexture().setDefaultBufferSize(((int) imageDimensions.width),
        ((int) imageDimensions.height));
    Log.d(TAG, "createCameraPreview: imgdimen" + imageDimensions.area());
    surface = new Surface(textureView.getSurfaceTexture());
    try {
      captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      captureRequestBuilder.addTarget(surface);

      cameraDevice.createCaptureSession(Arrays.asList(surface),
          sessionStateCallback, null);
      Log.d(TAG, "createCameraPreview: session active");
    } catch (CameraAccessException e) {
      Log.e(TAG, "createCameraPreview: " + e.getMessage());
    }


  }

  private void openCamera() {
    captureAvailable = true;
    Log.d(TAG, "openCamera: starting");
    try {
      cameraId = manager.getCameraIdList()[0];
      characteristics = manager.getCameraCharacteristics(cameraId);
      StreamConfigurationMap map = characteristics
          .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      imageDimensions = new Size(map.getOutputSizes(SurfaceTexture.class)[0].getWidth(),
          map.getOutputSizes(SurfaceTexture.class)[0].getHeight());
      if (ActivityCompat.checkSelfPermission(context, permission.CAMERA) == PERMISSION_GRANTED) {
        manager.openCamera(cameraId, stateCallback, null);
      }
    } catch (CameraAccessException e) {
      Log.e(TAG, "openCamera: " + e.getMessage());
    }
  }


  /**
   * Called on fragment resume, initializes thread and starts camera
   */
  @Override
  public void onResume() {
    super.onResume();
    Log.d(TAG, "starting background thread");
    startBackgroundThread();
    if (textureView.isAvailable()) {
      openCamera();
    } else {
      textureView.setSurfaceTextureListener(surfaceTextureListener);
    }
  }

  /**
   * On pause, kills threads and shuts down camera
   */
  @Override
  public void onPause() {
    closeCamera();
    stopBackgroundThread();
    super.onPause();
    Log.d(TAG, "onPause: ");
  }

  private void startBackgroundThread() {
    mBackgroundThread = new HandlerThread("camera_background");
    mBackgroundThread.start();
    mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
  }

  private void stopBackgroundThread() {
    mBackgroundThread.quit();

    try {
      mBackgroundThread.join();
      mBackgroundThread = null;
      mBackgroundHandler = null;
    } catch (InterruptedException e) {
      Log.e(TAG, "stopBackgroundThread: " + e.getMessage());
    }
  }

  private void closeCamera() {
    if (null != cameraDevice) {
      cameraDevice.close();
    }
    if (null != reader) {
      reader.close();
    }
  }

}
