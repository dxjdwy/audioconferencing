package se.ltu.M7017E.lab2.client.audio;

import lombok.Getter;

import org.gstreamer.Bin;
import org.gstreamer.Caps;
import org.gstreamer.Element;
import org.gstreamer.ElementFactory;
import org.gstreamer.GhostPad;
import org.gstreamer.Pad;
import org.gstreamer.PadLinkReturn;
import org.gstreamer.State;

import se.ltu.M7017E.lab2.client.Tool;

/**
 * Bin made to be added to the {@link ReceiverPipeline}. Receive from UDP
 * unicast and do RTP stuff.
 */
public class UnicastReceiver extends Bin {
	/** Name of _the_ unicast bin */
	private static final String RECEIVER_UNICAST = "receiver_unicast";

	private final Element udpSource;
	private final Element rtpBin;
	private Pad src;

	/** UDP port that has been automatically assigned from available ones */
	@Getter
	private int port = 0;

	/**
	 * Create a new {@link UnicastReceiver} and link everything needed.
	 * 
	 * @param connectSrcTo
	 *            As soon as our friend will have called us on this local port,
	 *            we will connect the src of this bin to this {@link Element}
	 */
	public UnicastReceiver(final Element connectSrcTo) {
		super(RECEIVER_UNICAST);

		// refer to GStreamer udpsrc plugin documentation
		udpSource = ElementFactory.make("udpsrc", null);
		udpSource.set("port", 0); // ask for a port

		/*
		 * set the caps from UDP, it flows downstream in the bin. Must match
		 * what is sent by everyone in the room of course
		 */
		Tool.successOrDie(
				"caps",
				udpSource
						.getStaticPad("src")
						.setCaps(
								Caps.fromString("application/x-rtp, media=(string)audio, "
										+ "clock-rate=(int)16000, encoding-name=(string)SPEEX, "
										+ "encoding-params=(string)1, payload=(int)110")));

		rtpBin = ElementFactory.make("gstrtpbin", null);

		/*
		 * when our friend starts emitting, a new SSRC appears on the stream and
		 * the plugin gstrtpbin automatically demux this and creates the
		 * specific pad
		 */
		rtpBin.connect(new Element.PAD_ADDED() {
			@Override
			public void padAdded(Element element, Pad pad) {
				if (pad.getName().startsWith("recv_rtp_src")) {
					// create elements
					RtpDecodeBin decoder = new RtpDecodeBin(false);

					// add them
					UnicastReceiver.this.add(decoder);

					// sync them
					decoder.syncStateWithParent();

					// link them
					Tool.successOrDie(
							"bin-decoder",
							pad.link(decoder.getStaticPad("sink")).equals(
									PadLinkReturn.OK));

					/*
					 * now that we have what we should connect to it, add the
					 * ghost pad
					 */
					src = new GhostPad("src", decoder.getStaticPad("src"));
					src.setActive(true);
					addPad(src);

					/*
					 * connect this UnicastReceiver to the Element we've been
					 * asked to do
					 */
					Tool.successOrDie("unicastreceiver-connectsrcto", Element
							.linkMany(UnicastReceiver.this, connectSrcTo));
				}
			}
		});

		// ############## ADD THEM TO PIPELINE ####################
		addMany(udpSource, rtpBin);

		// ###################### LINK THEM ##########################
		Pad pad = rtpBin.getRequestPad("recv_rtp_sink_0");
		Tool.successOrDie("udpSource-rtpbin", udpSource.getStaticPad("src")
				.link(pad).equals(PadLinkReturn.OK));

		/*
		 * get this ready for playing, after this the UDP port will have been
		 * assigned too
		 */
		pause();

		port = (Integer) udpSource.get("port");
	}

	/**
	 * Called to cleanly remove this Bin from its parent. Assumption: it was
	 * connected downstream through a request pad (that will also be cleanly
	 * released)
	 */
	public void getOut() {
		/*
		 * if we were connected to something downstream (may haven't been the
		 * cause if call was refused for example)
		 */
		Pad downstreamPeer = null;
		if (src != null) {
			// before disconnecting, remember the request pad we were linked to
			downstreamPeer = src.getPeer();
		}

		this.setState(State.NULL);

		((Bin) this.getParent()).remove(this);

		if (downstreamPeer != null) {
			// clean request pad from adder
			downstreamPeer.getParentElement().releaseRequestPad(downstreamPeer);
		}
	}
}
