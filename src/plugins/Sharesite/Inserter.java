package plugins.Sharesite;

import java.util.HashMap;
import java.util.LinkedList;
import java.awt.*;
import java.awt.image.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.RequestStarter;
import freenet.support.api.ManifestElement;
import freenet.support.io.ArrayBucket;
import java.io.*;

/**
 * Inserts new or the first editions of freesites.
 * The Sharesite freesites is inserted as
 * normal freesites, and can be accessed as such.
 */
public class Inserter extends Thread {
	private boolean running;
	private LinkedList<Freesite> queuedInserts;

	public Inserter() {
		running = true;
		queuedInserts = new LinkedList<Freesite>();
	}

	@Override
	public void run() {
		Freesite nextToInsert;

		while (true) {
			// Quick do everything that requires locking
			synchronized (this) {
				nextToInsert = null;

				if (!queuedInserts.isEmpty()) {
					nextToInsert = queuedInserts.removeFirst();
				}
			}

			// Now safely perform the blocking inserts
			if (nextToInsert != null) {
				performInsert(nextToInsert);
			}

			// If nothing to do, let the thread sleep until notify
			try {
				synchronized (this) {
					if (!running) break;
					if (!queuedInserts.isEmpty()) continue;

					wait();
				}
			} catch (InterruptedException e) {
			}
		}
	}

	public synchronized void terminate() {
		running = false;
		notify();
		// TODO: how do we terminate a running insert? it is blocking.
	}

	private synchronized boolean isTerminated() {
		return running == false;
	}

	public synchronized void add(Freesite freesite) {
		freesite.setL10nStatus("Status.Queue", freesite.getRealStatus());
		queuedInserts.addLast(freesite);
		notify();
	}

	private void performInsert(Freesite c) {
		c.setL10nStatus("Status.Inserting", "Status.InsertFailed");
		Plugin.instance.database.save();


		try {
			// prepare the buckets to insert
			HashMap<String,Object> bucketsByName = new HashMap<String,Object>();
            
			// Make buckets with the freesite
			ArrayBucket html = new ArrayBucket(c.getHTML().getBytes("UTF-8"));
			ArrayBucket css = new ArrayBucket(c.getCSS().getBytes("UTF-8"));
			ArrayBucket text = new ArrayBucket(c.getText().getBytes("UTF-8"));
			ArrayBucket keys = new ArrayBucket(c.getKeys().getBytes("UTF-8"));

            if (c.getActivelinkUri().equals(""))
                {
                    BufferedImage img = ActivelinkCreator.create(c.getName());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(5000);
                    ImageIO.write(img, "PNG", baos);
                    ArrayBucket activelinkData = new ArrayBucket(baos.toByteArray());
                    Plugin.instance.logger.putstr("444");
                    bucketsByName.put("activelink.png", activelinkData);
                }
            else
                {
                    FreenetURI activelinkURI = new FreenetURI(c.getActivelinkUri());
                    /* redirect to the activelinkUri */
                    /* TODO: discuss whether it is problematic that
                     * this might allow causing other sites to preload
                     * by using an image which is in the other container */
                    ManifestElement activelinkManifest = new ManifestElement("activelink.png", activelinkURI, null);
                    bucketsByName.put("activelink.png", activelinkManifest);
                }

			bucketsByName.put("index.html", html);
			bucketsByName.put("style.css", css);
			bucketsByName.put("source.txt", text);
			bucketsByName.put("keys.txt", keys);

			// Insert the new edition
			String suffix = c.getName() + "-" + (c.getEdition() + 1);
			FreenetURI insertURI = new FreenetURI(c.getInsertSSK() + suffix);
			insertURI = insertURI.uskForSSK();

			HighLevelSimpleClient simpleClient = Plugin.instance.pluginRespirator.getHLSimpleClient();
			Plugin.instance.logger.putstr(insertURI+": Insert starting");
			FreenetURI resultURI = simpleClient.insertManifest(insertURI, bucketsByName, "index.html", RequestStarter.INTERACTIVE_PRIORITY_CLASS);
			if (isTerminated()) return;

			// Mark as successfully updated
			c.setL10nStatus("Status.Current");
			c.setEdition(resultURI.getEdition());
			Plugin.instance.database.save();
			Plugin.instance.logger.putstr(insertURI+": Insert finished");
		} catch (Exception e) {
			// Mark as update failed
			c.setL10nStatus("Status.InsertFailed");

			StringWriter sw=new StringWriter();
			PrintWriter pw=new PrintWriter(sw);
			e.printStackTrace(pw);
			Plugin.instance.logger.putstr(e.getMessage());
		}
	}
}
