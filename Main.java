import com.pi4j.io.gpio.*;
import com.pi4j.io.i2c.*;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;
import java.io.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import javafx.animation.*;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.beans.value.*;
import javafx.event.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class Main extends Application {

  // get a handle to the GPIO controller
  private final GpioController gpio = GpioFactory.getInstance();
  // creating the pin object
  private final GpioPinDigitalOutput bell_pin = gpio.provisionDigitalOutputPin(
    RaspiPin.GPIO_06,
    "Bell",
    PinState.LOW
  );

  //ADC Commands
  private static final byte CMD_NEW_CNVRSN = (byte) 0x80; // Initiate a new conversion(One-Shot Conversion mode only)
  private static final byte CMD_MODE_CONT = 0x10; // Continuous Conversion Mode
  private static final byte CMD_MODE_ONESHOT = 0x00; // One-Shot Conversion Mode
  private static final byte CMD_CH_1 = 0x00; // Channel 1
  private static final byte CMD_CH_2 = 0x20; // Channel 2
  private static final byte CMD_CH_3 = 0x40; // Channel 3
  private static final byte CMD_CH_4 = 0x60; // Channel 4
  private static final byte CMD_SPS_240 = 0x00; // 240 SPS (12-bit)
  private static final byte CMD_SPS_60 = 0x04; // 60 SPS (14-bit)
  private static final byte CMD_SPS_15 = 0x08; // 15 SPS (16-bit)
  private static final byte CMD_SPS_3 = 0x0C; // 3.75 SPS (18-bit)
  private static final byte CMD_GAIN_1 = 0x00; // PGA Gain = 1V/V
  private static final byte CMD_GAIN_2 = 0x01; // PGA Gain = 2V/V
  private static final byte CMD_GAIN_4 = 0x02; // PGA Gain = 4V/V
  private static final byte CMD_GAIN_8 = 0x03; // PGA Gain = 8V/V
  private static final byte CMD_READ_CNVRSN = 0x00; // Read Conversion Result Data
  private static final byte address_1 = 0x6C; // X address
  private static final byte address_2 = 0x68; // Y address
  private static final byte address_3 = 0x6A; // Z address
  private static final byte CH1_CONFIG_CMD =
    (CMD_CH_1 | CMD_MODE_CONT | CMD_SPS_15 | CMD_GAIN_1);
  I2CBus i2c;
  I2CDevice adc_1;
  I2CDevice adc_2;
  I2CDevice adc_3;

  private Timeline timeline; //timeline object
  private static final Duration UPDATE_FREQUENCY = Duration.millis(100); // sample rate
  private int high_score_counter = 0; //counter for resetting high score on long press

  //voltages
  private float voltage_x = 0; //voltage for x
  private float voltage_y = 0; //voltage for y
  private float voltage_z = 0; //voltage for z

  //voltage offsets
  private float offset_x = 0; //offset for x
  private float offset_y = 0; //offset for y
  private float offset_z = 0; //offset for z

  //pound forces
  private float lb_x = 0; //pound force for x
  private float lb_y = 0; //pound force for y
  private float lb_z = 0; //pound force for z
  private float lb_combined = 0; //total pound force
  private float high_score = 50; //high score in lbs

  //conversion factor (lb/V)
  private float sensitivity_x = 1400; //sensitivity for x
  private float sensitivity_y = 1400; //sensitivity for y
  private float sensitivity_z = 1400; //sensitivity for z

  //shunt equivalence for each axis
  private float shunt_eq_x = 700; //shunt equivalence for x
  private float shunt_eq_y = 700; //shunt equivalence for y
  private float shunt_eq_z = 700; //shunt equivalence for z

  //stage
  Stage stage;

  //scences
  Scene main_scene;
  Scene shunt_scene;

  //pound labels
  private Label lbs_combined_label;
  private Label high_score_label;
  private Label lbs_x_label;
  private Label lbs_y_label;
  private Label lbs_z_label;

  //voltage labels
  private Label volt_x_label;
  private Label volt_y_label;
  private Label volt_z_label;

  //sensitivity labels
  private Label sensitivity_x_label;
  private Label sensitivity_y_label;
  private Label sensitivity_z_label;

  //chart variables
  private LineChart<Number, Number> scatter_chart; //scatter chart
  private XYChart.Series series; //scatter chart series
  private int time = 0; //time since  in ms
  private float time_sec = 0; //time in seconds
  private Queue<Float> q = new LinkedList<>(); //2 second queue of forces for setting upper bound
  private NumberAxis xAxis; //x axis
  private NumberAxis yAxis; //y axis

  //led strip variables
  private DotStar led_strip;
  private final int NUM_LEDS = 68;
  private float perc = 0;
  private int green = 255;
  private int red = 0;

  //bools for triggering high score
  private boolean zeroed = false;
  private boolean calibrated = false;

  //sets the offsets in all axes
  public void setOffsets() {
    offset_x = voltage_x;
    offset_y = voltage_y;
    offset_z = voltage_z;
    high_score = 50;
    high_score_label.setText(String.format("%1.2flbf ", high_score));
    bell_pin.low();
    zeroed = true;
  }

  //resets the offsets to 0 in all axes
  public void resetOffsets() {
    zeroed = false;
    offset_x = 0;
    offset_y = 0;
    offset_z = 0;
    bell_pin.low();
  }

  //sets the sensitivity in all axes
  public void calibrateAll() {
    sensitivity_x = voltage_x != 0 ? shunt_eq_x / voltage_x : 0;
    sensitivity_y = voltage_y != 0 ? shunt_eq_y / voltage_y : 0;
    sensitivity_z = voltage_z != 0 ? shunt_eq_z / voltage_z : 0;
    sensitivity_x_label.setText(String.format("%1.2flb/V", sensitivity_x));
    sensitivity_y_label.setText(String.format("%1.2flb/V", sensitivity_y));
    sensitivity_z_label.setText(String.format("%1.2flb/V", sensitivity_z));
    calibrated = true;
  }

  //updates the voltages lbs and labels
  public void updateValues() throws IOException, InterruptedException {
    //adc data
    byte data[] = { 0, 0 };
    byte result_arr[] = { 0, 0, 0, 0 };
    int raw_adc = 0;
    int err = 0;

    try {
      //read and format adc 1
      adc_1.write(CH1_CONFIG_CMD);
      while (err <= 0) err = adc_1.read(data, 0, 2);
      err = 0;
      result_arr[1] = data[0];
      result_arr[0] = data[1];
      raw_adc =
        ((result_arr[3] & 0xFF) << 24) |
        ((result_arr[2] & 0xFF) << 16) |
        ((result_arr[1] & 0xFF) << 8) |
        ((result_arr[0] & 0xFF) << 0);
      if (raw_adc > 32767) raw_adc = -1 * (raw_adc - 32767);
      voltage_x = (float) (((float) raw_adc / 32767) * 2.048);
      volt_x_label.setText(String.format("%1.4fV ", voltage_x - offset_x));

      //read and format adc 2
      adc_2.write(CH1_CONFIG_CMD);
      while (err <= 0) err = adc_2.read(data, 0, 2);
      err = 0;
      result_arr[1] = data[0];
      result_arr[0] = data[1];
      raw_adc =
        ((result_arr[3] & 0xFF) << 24) |
        ((result_arr[2] & 0xFF) << 16) |
        ((result_arr[1] & 0xFF) << 8) |
        ((result_arr[0] & 0xFF) << 0);
      if (raw_adc > 32767) raw_adc = -1 * (raw_adc - 32767);
      voltage_y = (float) (((float) raw_adc / 32767) * 2.048);
      volt_y_label.setText(String.format("%1.4fV ", voltage_y - offset_y));

      // //read and format adc 1  IF YOU WANT TO USE Z
      // adc_3.write(CH1_CONFIG_CMD);
      // while (err <= 0) err = adc_3.read(data, 0, 2);
      // err = 0;
      // result_arr[1] = data[0];
      // result_arr[0] = data[1];
      // raw_adc =
      //   ((result_arr[3] & 0xFF) << 24) |
      //   ((result_arr[2] & 0xFF) << 16) |
      //   ((result_arr[1] & 0xFF) << 8) |
      //   ((result_arr[0] & 0xFF) << 0);
      // if (raw_adc > 32767) raw_adc = -1 * (raw_adc - 32767);
      // voltage_z = (float) (((float) raw_adc / 32767) * 2.048);
      // volt_z_label.setText(String.format("%1.4fV ", voltage_z - offset_z));
    } catch (Exception e) {
      System.out.println("ADCs Not Responding");
    }

    //update lbs
    lb_x = Math.abs(sensitivity_x * (voltage_x - offset_x));
    lbs_x_label.setText(String.format("%1.2flbf ", lb_x));
    lb_y = Math.abs(sensitivity_x * (voltage_y - offset_y));
    lbs_y_label.setText(String.format("%1.2flbf ", lb_y));
    lb_z = Math.abs(sensitivity_z * (voltage_z - offset_z));
    lbs_z_label.setText(String.format("%1.2flbf ", lb_z));
    lb_combined = lb_x + lb_y + lb_z;
    lbs_combined_label.setText(String.format("%1.2flbf ", lb_combined));

    //update LED strip
    float scalar = lb_combined / high_score;
    if (scalar > 1 && zeroed && calibrated) {
      //new high score
      high_score = lb_combined > high_score ? lb_combined : high_score;
      high_score_label.setText(String.format("%1.2flbf ", high_score));
      bell_pin.high();//start bell
      ledRainbow();//led flash
      bell_pin.low();//stop bell
    } else {
      if (scalar > 1) scalar = 1;
      //display leds
      ledScale(scalar);
    }

    // update the line chart
    time += 100;
    time_sec = (float) time / 1000;
    series.getData().add(new XYChart.Data(time_sec, lb_combined));
    xAxis.setUpperBound(time_sec);
    q.add(lb_combined);
    if (time_sec > 2) {
      xAxis.setLowerBound(time_sec - 2);
      if (time_sec % 200 == 0) series.getData().remove(0, (200 - 2));
      q.remove();
    }
    yAxis.setUpperBound(Collections.max(q) + 1);
  }

  //displays a rainbow on the led strip
  public void ledRainbow() throws IOException, InterruptedException {
    for (int j = 0; j < 300; j++) { // change the max value to adjust the number of color changes
      for (int i = 0; i < NUM_LEDS; i++) led_strip.setPixelColor(
        i,
        (int) (Math.random() * 255),
        (int) (Math.random() * 255),
        (int) (Math.random() * 255)
      );
      led_strip.show();
      //Thread.sleep(5); // Use a delay if the flash sequence is too short
    }
    led_strip.clear();
    led_strip.show();
  }


  //displays the scale on the led strip with a green to red gradient
  public void ledScale(float scalar) throws IOException, InterruptedException {
    led_strip.clear();
    green = 120; //255 was too green so I went with 120
    for (int i = 0; i < Math.round(NUM_LEDS * scalar); i++) {
      perc = (float) i / NUM_LEDS;
      if (perc <= 0.5) red = (int) Math.round(2 * perc * 255); else green =
        (int) Math.round((1 - (2 * (perc - 0.5))) * 120);
      led_strip.setPixelColor(i, red, green, 0);
    }
    led_strip.show();
  }

  //returns an HBox of the combined force labels
  public HBox getForceCombinedLabels() {
    // Holder to align the items vertically
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //add Total: label
    Label total_label = new Label("Total: ");
    total_label.getStyleClass().add("header");
    box.getChildren().add(total_label);

    // Text label for displaying the total pound force
    lbs_combined_label = new Label("0.00lbf");
    lbs_combined_label.getStyleClass().add("header");
    box.getChildren().add(lbs_combined_label);

    return box;
  }

  //returns an HBox of the high score force labels
  public HBox getHighScoreLabels() {
    // Holder to align the items vertically
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //add Total: label
    Label high_score_title_label = new Label("High Score: ");
    high_score_title_label.getStyleClass().add("sub-header");
    box.getChildren().add(high_score_title_label);

    // Text label for displaying the total pound force
    high_score_label = new Label(String.format("%1.2flbf ", high_score));
    high_score_label.getStyleClass().add("sub-header");
    box.getChildren().add(high_score_label);

    return box;
  }

  //returns an HBox of the x axis labels
  public HBox getForceXLabels() {
    // Holder to align the items horizontally
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //add Fx: label
    Label fx_label = new Label("Fx: ");
    fx_label.getStyleClass().add("sub-header");
    box.getChildren().add(fx_label);

    // Text label for displaying the pound force of the x axis
    lbs_x_label = new Label("0.00lbf ");
    lbs_x_label.getStyleClass().add("sub-header");
    box.getChildren().add(lbs_x_label);

    // Text label for displaying the voltage of the x axis
    volt_x_label = new Label("0.0000V ");
    volt_x_label.getStyleClass().add("sub-header");
    box.getChildren().add(volt_x_label);

    return box;
  }

  //returns an HBox of the y axis labels
  public HBox getForceYLabels() {
    // Holder to align the items horizontally
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //add Fx: label
    Label fy_label = new Label("Fy: ");
    box.getChildren().add(fy_label);
    fy_label.getStyleClass().add("sub-header");

    // Text label for displaying the pound force of the x axis
    lbs_y_label = new Label("0.00lbf ");
    box.getChildren().add(lbs_y_label);
    lbs_y_label.getStyleClass().add("sub-header");

    // Text label for displaying the voltage of the x axis
    volt_y_label = new Label("0.0000V ");
    box.getChildren().add(volt_y_label);
    volt_y_label.getStyleClass().add("sub-header");

    return box;
  }

  //returns an HBox of the z axis labels
  public HBox getForceZLabels() {
    // Holder to align the items horizontally
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //add Fz: label
    Label fz_label = new Label("Fz: ");
    box.getChildren().add(fz_label);
    fz_label.getStyleClass().add("sub-header");

    // Text label for displaying the pound force of the x axis
    lbs_z_label = new Label("0.00lbf ");
    box.getChildren().add(lbs_z_label);
    lbs_z_label.getStyleClass().add("sub-header");

    // Text label for displaying the voltage of the x axis
    volt_z_label = new Label("0.0000V ");
    box.getChildren().add(volt_z_label);
    volt_z_label.getStyleClass().add("sub-header");

    return box;
  }

  //returns a VBox of the force labels
  public VBox getForceLabels() {
    // Holder to align the items vertically
    VBox box = new VBox(
      getForceCombinedLabels(),
      getHighScoreLabels(),
      getForceXLabels(),
      getForceYLabels()
    );
    getForceZLabels();// put this in the box to show the z labels and uncomment the adc 3 read in the updateValues method

    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));
    box.setPrefWidth(800.0);

    return box;
  }

  //returns a HBox of the buttons
  public HBox getButtons() {
    // Holder to align the items vertically
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    // Button to zero voltages
    Button zero_button = new Button("Zero All");
    zero_button.setOnAction(e -> setOffsets());

    // Button to reset offsets
    Button reset_button = new Button("Reset Offsets");
    //reset_button.setOnAction(e -> resetOffsets());
    reset_button.addEventFilter(
      MouseEvent.ANY,
      new EventHandler<MouseEvent>() {
        long startTime;

        @Override
        public void handle(MouseEvent event) {
          if (event.getEventType().equals(MouseEvent.MOUSE_PRESSED)) {
            startTime = System.currentTimeMillis();
          } else if (event.getEventType().equals(MouseEvent.MOUSE_RELEASED)) {
            if (System.currentTimeMillis() - startTime > 2 * 1000) {
              high_score = 0;
            } else resetOffsets();
          }
        }
      }
    );

    // Button to switch to shunt screen
    Button shunt_button = new Button("Shunt");
    shunt_button.setOnAction(e -> stage.setScene(shunt_scene));
    shunt_scene.setCursor(Cursor.NONE); //hide cursor

    // Button to exit the application
    Button exit_button = new Button("Exit");
    exit_button.setOnAction(
      e -> ((Stage) exit_button.getScene().getWindow()).close()
    );

    box
      .getChildren()
      .addAll(zero_button, reset_button, shunt_button, exit_button);

    return box;
  }

  //returns a HBox of the shunt buttons
  public HBox getShuntButtons() {
    // Holder to align the items vertically
    HBox box = new HBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    // Button to switch to shunt screen
    Button back_button = new Button("Back");
    back_button.setOnAction(e -> stage.setScene(main_scene));
    main_scene.setCursor(Cursor.NONE); //hide cursor

    // Button to zero voltages
    Button calibrate_button = new Button("Calibrate");
    calibrate_button.setOnAction(e -> calibrateAll());

    box.getChildren().addAll(back_button, calibrate_button);

    return box;
  }

  //returns a VBox of the sensitivity labels
  public VBox getSensitivityLabels() {
    // Holder to align the items vertically
    VBox box = new VBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //x
    Label sensitivity_x_title = new Label("Sensitivity X: ");
    sensitivity_x_title.getStyleClass().add("sub-header");
    sensitivity_x_label = new Label(String.format("%1.2flb/V", sensitivity_x));
    sensitivity_x_label.getStyleClass().add("sub-header");

    //y
    Label sensitivity_y_title = new Label("Sensitivity Y: ");
    sensitivity_y_title.getStyleClass().add("sub-header");
    sensitivity_y_label = new Label(String.format("%1.2flb/V", sensitivity_y));
    sensitivity_y_label.getStyleClass().add("sub-header");

    //z
    Label sensitivity_z_title = new Label("Sensitivity Z: ");
    sensitivity_z_title.getStyleClass().add("sub-header");
    sensitivity_z_label = new Label(String.format("%1.2flb/V", sensitivity_z));
    sensitivity_z_label.getStyleClass().add("sub-header");

    box
      .getChildren()
      .addAll(
        sensitivity_x_title,
        sensitivity_x_label,
        sensitivity_y_title,
        sensitivity_y_label,
        sensitivity_z_title,
        sensitivity_z_label
      );

    return box;
  }

  //returns a VBox of the shunt equivalent text boxes
  public VBox getShuntEqBox() {
    // Holder to align the items vertically
    VBox box = new VBox();
    box.setSpacing(20);
    box.setPadding(new Insets(0, 20, 10, 20));

    //text fields for setting the shut equivalents
    //x
    Label shunt_eq_x_label = new Label("Shunt Equivalent X: ");
    shunt_eq_x_label.getStyleClass().add("sub-header");
    TextField shunt_eq_x_field = new TextField(
      String.format("%1.4f", shunt_eq_x)
    );
    shunt_eq_x_field
      .textProperty()
      .addListener(
        new ChangeListener<String>() {
          @Override
          public void changed(
            ObservableValue<? extends String> observable,
            String oldValue,
            String newValue
          ) {
            if (!newValue.matches("\\d{0,7}([\\.]\\d{0,4})?")) {
              shunt_eq_x_field.setText(oldValue);
            } else {
              shunt_eq_x = Float.parseFloat(newValue);
            }
          }
        }
      );
    //y
    Label shunt_eq_y_label = new Label("Shunt Equivalent Y: ");
    shunt_eq_y_label.getStyleClass().add("sub-header");
    TextField shunt_eq_y_field = new TextField(
      String.format("%1.4f", shunt_eq_y)
    );
    shunt_eq_y_field
      .textProperty()
      .addListener(
        new ChangeListener<String>() {
          @Override
          public void changed(
            ObservableValue<? extends String> observable,
            String oldValue,
            String newValue
          ) {
            if (!newValue.matches("\\d{0,7}([\\.]\\d{0,4})?")) {
              shunt_eq_y_field.setText(oldValue);
            } else {
              shunt_eq_y = Float.parseFloat(newValue);
            }
          }
        }
      );
    //z
    Label shunt_eq_z_label = new Label("Shunt Equivalent Z: ");
    shunt_eq_z_label.getStyleClass().add("sub-header");
    TextField shunt_eq_z_field = new TextField(
      String.format("%1.4f", shunt_eq_z)
    );
    shunt_eq_z_field
      .textProperty()
      .addListener(
        new ChangeListener<String>() {
          @Override
          public void changed(
            ObservableValue<? extends String> observable,
            String oldValue,
            String newValue
          ) {
            if (!newValue.matches("\\d{0,7}([\\.]\\d{0,4})?")) {
              shunt_eq_z_field.setText(oldValue);
            } else {
              shunt_eq_z = Float.parseFloat(newValue);
            }
          }
        }
      );

    box
      .getChildren()
      .addAll(
        shunt_eq_x_label,
        shunt_eq_x_field,
        shunt_eq_y_label,
        shunt_eq_y_field,
        shunt_eq_z_label,
        shunt_eq_z_field
      );

    return box;
  }

  // line chart for displaying the force over time
  public LineChart<Number, Number> getScatterChart() {
    //Defining the x axis
    xAxis = new NumberAxis(0, 100, 100);
    xAxis.setLabel("Time (ms)");

    //Defining the y axis
    yAxis = new NumberAxis(0, 2, 2000);
    yAxis.setLabel("Force (lbf)");

    //Creating the Scatter chart
    scatter_chart = new LineChart<Number, Number>(xAxis, yAxis);
    scatter_chart.setAnimated(false);
    scatter_chart.setLegendVisible(false);
    //scatter_chart.setCreateSymbols(false); //remove points

    //Prepare XYChart.Series objects by setting data
    series = new XYChart.Series();
    series.getData().add(new XYChart.Data(0, 0));

    //Setting the data to scatter chart
    scatter_chart.getData().add(series);

    return scatter_chart;
  }

  @Override
  public void start(Stage stage)
    throws FileNotFoundException, IOException, UnsupportedBusNumberException, InterruptedException {
    //initialize ADCs
    i2c = I2CFactory.getInstance(I2CBus.BUS_1);
    adc_1 = i2c.getDevice(address_1);
    adc_2 = i2c.getDevice(address_2);
    adc_3 = i2c.getDevice(address_3);
    //initialize LED strip
    led_strip = new DotStar(NUM_LEDS);

    stage.initStyle(StageStyle.UNDECORATED);
    this.stage = stage;

    //The root node
    GridPane root = new GridPane();
    root.setId("root");
    root.setHgap(20);
    root.setVgap(20);
    root.setPadding(new Insets(20, 2, 10, 2));
    FileInputStream img = new FileInputStream(
      "/home/pi/Desktop/3DARMDEMO/fair.png"
    );
    root.setBackground(
      new Background(
        new BackgroundImage(
          new Image(img),
          BackgroundRepeat.NO_REPEAT,
          BackgroundRepeat.NO_REPEAT,
          BackgroundPosition.DEFAULT,
          new BackgroundSize(
            BackgroundSize.AUTO,
            BackgroundSize.AUTO,
            true,
            true,
            false,
            true
          )
        )
      )
    );

    // Add root to the scene
    main_scene = new Scene(root, 1024, 600);
    main_scene
      .getStylesheets()
      .add(getClass().getResource("armdemo.css").toExternalForm());

    // Add force measurements
    root.add(getForceLabels(), 0, 0, 6, 5);

    //Add scatter chart
    HBox chart_group = new HBox();
    chart_group.setPrefWidth(400.0);
    chart_group.getChildren().add(getScatterChart());
    root.add(chart_group, 6, 0, 1, 5);

    //Add buttons
    root.add(getButtons(), 0, 10, 7, 2);

    //update values
    timeline =
      new Timeline(
        new KeyFrame(
          Duration.ZERO,
          new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent actionEvent) {
              try {
                updateValues();
              } catch (Exception e) {}
            }
          }
        ),
        new KeyFrame(UPDATE_FREQUENCY)
      );
    timeline.setCycleCount(Timeline.INDEFINITE);
    timeline.play();

    //setup shunt scene
    //The root node
    GridPane shunt_root = new GridPane();
    shunt_root.setHgap(20);
    shunt_root.setVgap(20);
    shunt_root.setPadding(new Insets(20, 2, 10, 2));
    shunt_root.setStyle("-fx-background-color: darkblue");

    // Add root to the scene
    shunt_scene = new Scene(shunt_root, 1024, 600);
    shunt_scene
      .getStylesheets()
      .add(getClass().getResource("armdemo.css").toExternalForm());

    //shunt buttons
    shunt_root.add(getShuntButtons(), 0, 2);

    //sensitivity labels
    shunt_root.add(getSensitivityLabels(), 0, 0, 1, 2);

    //shunt equivalent text boxes
    shunt_root.add(getShuntEqBox(), 1, 0, 2, 2);

    // The top level JavaFX container
    stage.setTitle("3D Arm Demo");
    main_scene.setCursor(Cursor.NONE); // hide cursor
    shunt_scene.setCursor(Cursor.NONE); // hide cursor
    stage.setScene(main_scene);
    stage.setMaximized(true);
    stage.show();
  }

  public static void main(String[] args) {
    launch();  // launches the application
  }
}
