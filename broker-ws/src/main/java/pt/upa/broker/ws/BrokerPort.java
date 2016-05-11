package pt.upa.broker.ws;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Timer;
import java.util.TimerTask;

import static javax.xml.ws.BindingProvider.ENDPOINT_ADDRESS_PROPERTY;

import java.util.ArrayList;
import java.util.Collection;

import javax.jws.WebService;
import javax.xml.ws.BindingProvider;
import javax.xml.registry.JAXRException;

import pt.ulisboa.tecnico.sdis.ws.uddi.UDDINaming;
import pt.upa.transporter.ws.*;
import pt.upa.transporter.ws.cli.*;

import pt.upa.Location;

@WebService(
endpointInterface="pt.upa.broker.ws.BrokerPortType",
wsdlLocation="broker.2_0.wsdl",
name="UpaBroker",
portName="BrokerPort",
targetNamespace="http://ws.broker.upa.pt/",
serviceName = "BrokerService"
)

public class BrokerPort implements BrokerPortType{

  TreeMap<String, TransporterClient> transporters = new TreeMap<String, TransporterClient>();
  ArrayList<Transport> _transports = new ArrayList<Transport>();
  int _idCounter;
  private Timer _timer;
  BrokerPortType _broker;
  boolean _primary;
  private String _url;

  public BrokerPort() throws Exception{
    connectToTransporters();
    System.out.println("Broker Initalized.");
  }

  public BrokerPort(boolean primary, String url) throws Exception{
    _url = url;
    _primary = primary;
    if(primary){
      connectToTransporters();
    }
    else{
      connectToBroker();
      _broker.sendInfo(url);
      stillAlive(10);
    }
    System.out.println("Broker Initalized.");
  }

  public void connectToBroker() throws Exception{
    String uURL = "http://localhost:9090";
    UDDINaming uddiNaming = new UDDINaming(uURL);
    String epAddress = uddiNaming.lookup("UpaBroker");

    if(epAddress == null){
      System.out.println("Broker not found");
      return;
    }
    else System.out.printf("Connected to broker @%s%n", epAddress);

    BrokerService service = new BrokerService();

    _broker = service.getBrokerPort();

    BindingProvider bindingProvider = (BindingProvider) _broker;

    Map<String, Object> requestContext = bindingProvider.getRequestContext();
    requestContext.put(ENDPOINT_ADDRESS_PROPERTY, epAddress);
  }

  public void connectToBrokerByURI(String endp) {
    try{
      BrokerPortType port = null;
      BrokerService service = new BrokerService();
      port = service.getBrokerPort();
      System.out.println("Setting endpoint address ...");
      BindingProvider bindingProvider = (BindingProvider) port;
      Map<String, Object> requestContext = bindingProvider.getRequestContext();
      requestContext.put(ENDPOINT_ADDRESS_PROPERTY, endp);
      System.out.println("Connection succesful to " + endp);
      _broker = port;
    } catch(Exception e){
      System.out.printf("Caught exception: %s%n", e);
      e.printStackTrace();
    }
  }

  private void connectToTransporters() throws JAXRException{
    System.out.println("Checking for Transporters.");

    String uURL = "http://localhost:9090";
    UDDINaming uddiNaming = new UDDINaming(uURL);
    Collection<String> endpoints = uddiNaming.list("UpaTransporter%");
    System.out.println("Creating clients to connect to " + endpoints.size() + " Transporters");
    createTransporterClients(endpoints);

  }

  private void createTransporterClients( Collection<String> endpoints) throws JAXRException{
    String name;
    for (String endp : endpoints) {
      TransporterClient client = new TransporterClient();
      try{
        client.connectToTransporterByURI(endp);
        name = client.ping();
        transporters.put(name,client);
        System.out.println("Transporter: " + name + " was added to broker");
      } catch(Exception e){e.printStackTrace();}
    }

  }

  public String ping(String name){
    String res = new String();
    System.out.printf("Pinging all transporters%n");
    for (Map.Entry<String, TransporterClient> entry : transporters.entrySet()){
      entry.getValue().ping();
    }
    return "Contact has been established with " + transporters.size() + " Transporters.\n";
  }

  protected Transport getTransportById(String id){
    for(Transport t : _transports){
      if(t.getId().equals(id)){
        return t;
      }
    }
    return null;
  }


  public TransportView viewTransport(String id) throws UnknownTransportFault_Exception{
    Transport tv = getTransportById(id);
    if(tv == null){
      UnknownTransportFault utf = new UnknownTransportFault();
      utf.setId(id);
      throw new UnknownTransportFault_Exception("Transport with " + id + " not found", utf);
    }
    String name = tv.getTransporterCompany();
    TransporterClient tpt = transporters.get(name);
    JobStateView state = tpt.jobStatus(id).getJobState();
    switch (state) {
      case HEADING:
        tv.setState(TransportStateView.HEADING);
        break;

      case ONGOING:
        tv.setState(TransportStateView.ONGOING);
        break;

      case COMPLETED:
        tv.setState(TransportStateView.COMPLETED);
        break;

      default:
        break;
    }

    return tv.toTransportView();
  }

  public void clearTransports(){
    int i = 0;
    System.out.printf("Executing order 66.%n");
    for (Map.Entry<String, TransporterClient> entry : transporters.entrySet()){
      entry.getValue().clearJobs();
      System.out.printf("Order 66 on transporter %d%n", i+1);
      i++;
    }
    _transports.clear();
    System.out.println("The Sith Shall Rule again.");
  }

  public List<TransportView> listTransports(){
    ArrayList<TransportView> _tviews = new ArrayList<TransportView>();
    for(Transport t : _transports){
      _tviews.add(t.toTransportView());
    }
    return _tviews;
  }

  public void checkBestTransporter (ArrayList<JobView> jobs, Transport transport){
    int min = 101;
    JobView bestJob = null;
    for(JobView job : jobs){
      if(job.getJobPrice() < min){
        transport.setTransporterCompany(job.getCompanyName());
        transport.setPrice(job.getJobPrice());
        transport.setId(job.getJobIdentifier());
        min = job.getJobPrice();
      }
    }
  }

  public void confirmJob(ArrayList<JobView> jobs, Transport transport){
    for (JobView job : jobs){
      try{
        if (transport.getTransporterCompany().equals(job.getCompanyName()))
          transporters.get(job.getCompanyName()).decideJob(job.getJobIdentifier(), true);

        else transporters.get(job.getCompanyName()).decideJob(job.getJobIdentifier(), false);

      } catch (BadJobFault_Exception e){
        e.printStackTrace();
      }
    }
  }

  public void rejectAllOptions (ArrayList<JobView> jobs){
    for (JobView job : jobs){
      try{
        transporters.get(job.getCompanyName()).decideJob(job.getJobIdentifier(), false);
      } catch (BadJobFault_Exception e){
        e.printStackTrace();
      }
    }
  }

  public ArrayList<JobView> makeRequests(String origin, String destination, int price)
    throws InvalidPriceFault_Exception, UnknownLocationFault_Exception{
    ArrayList<JobView> _jobs = new ArrayList<JobView>();
    for (Map.Entry<String, TransporterClient> entry : transporters.entrySet()){
      try{
        JobView proposedJob = entry.getValue().requestJob(origin, destination, price);
        if (proposedJob != null) _jobs.add(proposedJob);
      }
      catch (BadLocationFault_Exception e) {
        UnknownLocationFault ulf = new UnknownLocationFault();
        ulf.setLocation(e.getFaultInfo().getLocation());
        throw new UnknownLocationFault_Exception("Invalid location:", ulf);
      }
      catch (BadPriceFault_Exception e) {
        InvalidPriceFault ipf = new InvalidPriceFault();
        ipf.setPrice(e.getFaultInfo().getPrice());
        throw new InvalidPriceFault_Exception("Invalid price:", ipf);
      }
    }
    return _jobs;
  }


  public String requestTransport(String origin, String destination, int priceMax)
    throws InvalidPriceFault_Exception, UnavailableTransportFault_Exception,
                    UnavailableTransportPriceFault_Exception, UnknownLocationFault_Exception{
             transporters.clear();
             try{
               connectToTransporters();
             } catch(JAXRException e) {e.printStackTrace();}

             Location l_origin = Location.fromValue(origin);
             Location l_destination = Location.fromValue(destination);

             if(l_origin == null){
               UnknownLocationFault ulf = new UnknownLocationFault();
               ulf.setLocation(origin);
               throw new UnknownLocationFault_Exception("Invalid origin:", ulf);
             }

             if(l_destination == null){
               UnknownLocationFault ulf = new UnknownLocationFault();
               ulf.setLocation(destination);
               throw new UnknownLocationFault_Exception("Invalid destination:", ulf);
             }

             if(priceMax <= 0) {
               InvalidPriceFault ipf = new InvalidPriceFault();
               ipf.setPrice(priceMax);
               throw new InvalidPriceFault_Exception("Invalid price:", ipf);
             }

             Transport newTransport = new Transport(origin, destination, priceMax, TransportStateView.REQUESTED);

             ArrayList<JobView> _jobs = makeRequests(origin, destination, priceMax);

             if(_jobs.isEmpty()){
               newTransport.setState(TransportStateView.FAILED);
               UnavailableTransportFault utf = new UnavailableTransportFault();
               utf.setOrigin(origin);
               utf.setDestination(destination);
               throw new UnavailableTransportFault_Exception("No Transport Available", utf);
             }

             checkBestTransporter(_jobs, newTransport);

             newTransport.setState(TransportStateView.BUDGETED);

             if(newTransport.getPrice() > priceMax){
               rejectAllOptions(_jobs);
               newTransport.setState(TransportStateView.FAILED);
               UnavailableTransportPriceFault utpf = new UnavailableTransportPriceFault();
               utpf.setBestPriceFound(newTransport.getPrice());
               throw new UnavailableTransportPriceFault_Exception("Price above maximum given by client", utpf);
             }

             else{
               confirmJob(_jobs, newTransport);
               newTransport.setState(TransportStateView.BOOKED);
             }
             _transports.add(newTransport);
             return newTransport.getId();
  }

  protected void stillAlive(int time) throws Exception{
    TimerTask _timerTask = new TimerTask() {
      @Override
      public void run() {
        try{
          _broker.ping("ping");
          System.out.println("STILL ALIVE and kicking");
          stillAlive(10);
        } catch (Exception e) {
          try{
            GYST(); 
          } catch (Exception eg){}
        }
      }
    };
    if(_timer != null)
      _timer.cancel();
    _timer = new Timer();
    _timer.schedule(_timerTask, time*1000);
  }

  private void GYST() throws Exception{

    System.out.println("ASSUMING CONTROL"); 
    connectToTransporters();
    System.out.println("All your base are belong to us");
    String uURL = "http://localhost:9090";
    UDDINaming uddiNaming = new UDDINaming(uURL);
    uddiNaming.rebind("UpaBroker", _url);
  }

  public void update(TransportView transport) {
    Transport transportToUpdate = getTransportById(transport.getId());
    if (transportToUpdate == null) {
      transportToUpdate = new Transport(transport);
      _transports.add(transportToUpdate);
      return ;
    }
    transportToUpdate.setState(transport.getState());
  }

  public void sendInfo(String url){
    connectToBrokerByURI(url);
  }

}
