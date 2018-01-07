import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;



public class GBNSender {
	static int data_size = 1024;			// (sN:2, data<=1024) Bytes : 1026 Bytes total
	int win_size;
	int timeoutVal;		
	
	short base;					
	short nextSN;			
	
	String path;				
	//static String fileName = "output.txt";
	Vector<byte[]> packetBuffer;	
	Timer timer;				
	Semaphore s;				
	int total_num_packets;	
	
	private static final ByteOrder BYTE_ORDER;

    static {
        BYTE_ORDER = ByteOrder.BIG_ENDIAN;
    }
	boolean isComplete;	
	
	// to start or stop the timer
	public void setTimer(boolean isNew){
		if (timer != null) timer.cancel();
		if (isNew){
			timer = new Timer();
			timer.schedule(new Timeout(), timeoutVal);
		}
	}
	
	public byte[] copyOfRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}	
	
	
		
	//class outthread
	public class OutThread extends Thread {
		
		private DatagramSocket out;
		private int dst_port;
		private InetAddress dst_addr;
		private int recv_port;


		// OutThread constructor
		public OutThread(DatagramSocket out, int dst_port, String dst_addr, int recv_port) throws UnknownHostException {
			this.out = out;
			this.dst_port = dst_port;
			this.recv_port = recv_port;
			this.dst_addr = InetAddress.getByName(dst_addr);;
		}
		
		public byte[] generatePacket(short sN, byte[] dataBytes) {
			byte[] sNBytes = ByteBuffer.allocate(2).order(BYTE_ORDER).putShort(sN).array(); 
			ByteBuffer packet = ByteBuffer.allocate(2 + dataBytes.length);
			packet.put(sNBytes);
			packet.put(dataBytes);
			return packet.array();
		}
		
		// sending process (updates nextSN)
				public void run(){
					try{
						FileInputStream fis = new FileInputStream(new File(path));
						total_num_packets = (int) Math.ceil((double)fis.getChannel().size()/1024);
						//System.out.println("The total number of packets in the stream is "+ total_num_packets); 
						try {
							
							while (!isComplete){
								if (nextSN < base + win_size){
									
									s.acquire();	/***** enter CS *****/
									if (base == nextSN) setTimer(true);
									
									byte[] out_data = new byte[10];
									boolean isFinalSN = false;
										
									
									// if reading not for the first time, take from the buffer 
									if (nextSN < packetBuffer.size()){
										out_data = packetBuffer.get(nextSN);
									}
									// else construct packet and add to list
									else{						
										byte[] dataBuffer = new byte[data_size];
										int dataLength = fis.read(dataBuffer, 0, data_size);
										System.out.println("The length of the data read is " + dataLength);
										// if the last chunk of data, isFinalSN is true
										if (dataLength == -1){
											isFinalSN = true;
										}
										// else if valid data
										else{
											byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
											out_data = generatePacket(nextSN, dataBytes);
										}
										
										packetBuffer.add(out_data);	
									}
									
									// send the packet
									if(!isFinalSN && !isComplete) {
										out.send(new DatagramPacket(out_data, out_data.length, dst_addr, dst_port));
										System.out.println("Sender: Sent sequence number " + nextSN);
									}
									
									// update nextSN if currently not the last chunk from the file input stream
									if (!isFinalSN) 
										nextSN++;
//									else
//										break;
									s.release();	/***** leave CS *****/
								}
								sleep(5);
							}
						} catch (Exception e) {
							e.printStackTrace();
						} finally {
							setTimer(false);	
							out.close();		
							fis.close();		
							System.out.println("Sender: outgoing channel closed!");
						}
					} catch (Exception e) {
						e.printStackTrace();
						System.exit(-1);
					}
				}

	}//end of class outthread
	
	//class inthread
	public class InThread extends Thread{
		private DatagramSocket in;
		private short lastAcked = 0;


		public InThread(DatagramSocket in) {
			this.in = in;
		}


		short decodePacket(byte[] pkt){
			byte[] ackNumBytes = copyOfRange(pkt, 0, 2);
//			byte temp = ackNumBytes[0];
//			ackNumBytes[0] = ackNumBytes[1];
//			ackNumBytes[1] = temp;
			return ByteBuffer.wrap(ackNumBytes).getShort();
		}
		

		public void run() {
			try {
				byte[] in_data = new byte[2];	
				DatagramPacket in_pkt = new DatagramPacket(in_data,	in_data.length);
				
				try {
					
					while (!isComplete) {
						
						in.receive(in_pkt);
						short ackNum = decodePacket(in_data);
						System.out.println("Sender: Received Ack " + ackNum);
						
						
//						if(ackNum == lastAcked+1) {
//							
//						}
						// if duplicate ack
						if (base == ackNum + 1){
							s.acquire();	/***** enter CS *****/
							setTimer(false);	
							nextSN = base;		
							s.release();	/***** leave CS *****/
						}
//						// else if last ack
						else if (ackNum == total_num_packets-1) isComplete = true;
//						// else normal ack
						else{
							base = ackNum++;
							s.acquire();	/***** enter CS *****/
							if (base == nextSN) 
								setTimer(false);	
							else 
								setTimer(true);						
							s.release();	/***** leave CS *****/
						}
					
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					in.close();
					System.out.println("Sender: receiving channel closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}//end of class inthread
	
	//class timeout
	public class Timeout extends TimerTask{
		public void run(){
			try{
				s.acquire();	
				System.out.println("Sender: Timeout!");
				nextSN = base;	
				s.release();	
			} catch(InterruptedException e){
				e.printStackTrace();
			}
		}
	}//end of class timeout
	
	
	
	//sender
	public GBNSender(String file_path, String receiver_ip_address, int receiver_port, int window_size_N, int timeout) {
		base = 1;
		nextSN = 1;
		this.path = file_path;
		this.timeoutVal = timeout;
		this.win_size = window_size_N;
		
		packetBuffer = new Vector<byte[]>(win_size);
		isComplete = false;
		DatagramSocket out, in;
		s = new Semaphore(1);
		
		
		try {

			out = new DatagramSocket(0);				
			in = new DatagramSocket(0);

			InThread thIn = new InThread(out);
			OutThread thOut = new OutThread(out, receiver_port, receiver_ip_address, out.getLocalPort());
			thIn.start();
			thOut.start();
			thIn.join();
			thOut.join();
			
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		
	}//end of sender 
	

		
	public static void main(String[] args) {
		
		if (args.length != 5) {
			System.err.println("Usage: java Sender file_path, receiver_ip_address, receiver_port, window_size_N, retransmission_timeout");
			System.exit(-1);
		}
		else { 
			long startTime = System.currentTimeMillis();
			new GBNSender(args[0], args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
			long stopTime = System.currentTimeMillis();
		    long elapsedTime = stopTime - startTime;
		    System.out.println("The elapsed time: " + elapsedTime/1000);
		}
	}
}
