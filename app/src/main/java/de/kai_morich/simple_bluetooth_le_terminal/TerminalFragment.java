package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.FileDescriptor;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    static final int FETCH_IMAGE_REQUEST = 12; //Just a number to identify the request

    static final byte[] TOKEN_STARTUP = {(byte)0xFF,(byte)0x00,(byte)0x80};

    static final double GAMMA = 3.2; //Still to low, it seems...
    static final int LEDCOUNT = 83;
    static final int CENTREOFFSET = 5;
    static final int IMGSIZE = 176; //83 LEDS, first 5 are not visible, gives 88, times two = 176
    static final int BUFSIZE = 249; //83 LEDS, times three (RGB) = 249

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private ImageView imageView;

    private Bitmap theImage;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean imageSending = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        imageView = view.findViewById(R.id.imageView);
        imageView.setClipToOutline(true);

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //We've got the intent to open a document
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                //Result is handled in a callback function
                startActivityForResult(intent, FETCH_IMAGE_REQUEST);
            }
        });

        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,Intent resultData) {
        if (requestCode == FETCH_IMAGE_REQUEST && resultCode ==Activity.RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                showImage(uri);
            }
        }
    }

    private byte gammaCorrection(int color) {
        double orig = (double)(color) / (255.0);
        double adj = Math.pow(orig,GAMMA) * (255.0);
        return (byte)adj;
    }

    private void showImage(Uri uri) {
        // Next: show the image
        ContentResolver contentResolver = getActivity().getContentResolver();
        try {
            ParcelFileDescriptor parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r");
            FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            Bitmap originalImage = BitmapFactory.decodeFileDescriptor(fileDescriptor);
            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            ExifInterface exif = new ExifInterface(fileDescriptor);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            if (orientation==ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90);
            }
            if (orientation==ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180);
            }
            if (orientation==ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270);
            }
            Bitmap croppedImage;
            if (height > width) {
              croppedImage = Bitmap.createBitmap(originalImage, 0, (height - width) / 2, width, width, matrix, true);
            } else {
              croppedImage = Bitmap.createBitmap(originalImage, (width - height) / 2, 0, height, height, matrix, true);
            }
            parcelFileDescriptor.close();
            theImage = Bitmap.createScaledBitmap(croppedImage,IMGSIZE,IMGSIZE,true);
            imageView.setImageBitmap(theImage);

            //After the TOKEN_STARTUP, we expect the receiver to send the theta-index it wants
            //So nothing else will happen here: see the "receive" routine for everything else!
            status("sending image");
            imageSending = true;
            service.write(TOKEN_STARTUP);

        } catch (Exception e) {
            //Nothing to do if file is not found
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                //data = (str + newline).getBytes();
                data = str.getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(byte[] data) {
        //We expect from the sender a theta-index
        //We receive this theta-index, and send the correponding line in return
        //This interaction ensures that no data is lost due to buffer overflows:
        //the sender and receiver will wait on each other
        if (imageSending) {
            if (data[0]==119) {
                receiveText.append("succes\n");
                imageSending = false;
            } else {
                receiveText.append(".");
            }
            double theta360 = data[0] * 3; //Send the theta-index that the receiver has requested
            double theta = (theta360 / 180.0) * Math.PI;
            byte[] buffer = new byte[BUFSIZE];
            for (int r = 0; r < LEDCOUNT; r++) {
                int x = (int) Math.round(Math.cos(theta) * (r+CENTREOFFSET) + (IMGSIZE/2));
                int y = (int) Math.round(Math.sin(theta) * (r+CENTREOFFSET) + (IMGSIZE/2));
                int color = theImage.getPixel(x, y);
                buffer[r * 3] = gammaCorrection(Color.red(color));
                buffer[r * 3 + 1] = gammaCorrection(Color.green(color));
                buffer[r * 3 + 2] = gammaCorrection(Color.blue(color));
            }
            try {
                service.write(buffer); //Write the requested line (per theta-index)
            } catch (Exception e) {
                onSerialIoError(e);
            }
        } else {
            //Originele routine
            if (hexEnabled) {
                receiveText.append(TextUtil.toHexString(data) + '\n');
            } else {
                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        Editable edt = receiveText.getEditableText();
                        if (edt != null && edt.length() > 1)
                            edt.replace(edt.length() - 2, edt.length(), "");
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
