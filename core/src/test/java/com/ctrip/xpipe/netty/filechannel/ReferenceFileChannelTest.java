package com.ctrip.xpipe.netty.filechannel;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.ctrip.xpipe.AbstractTest;
import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;

/**
 * @author wenchao.meng
 *
 *         Nov 10, 2016
 */
public class ReferenceFileChannelTest extends AbstractTest {

	private ReferenceFileChannel referenceFileChannel;
	private int totalFileLen = 1 << 20;

	@Before
	public void beforeReferenceFileChannelTest() throws FileNotFoundException, IOException {

		String file = getTestFileDir() + "/" + getTestName();

		try (FileOutputStream ous = new FileOutputStream(new File(file))) {
			ous.write(randomString(totalFileLen).getBytes());
		}

		referenceFileChannel = new ReferenceFileChannel(new File(file));
	}

	@Test
	public void testCloseAfterRelease() throws IOException {

		ReferenceFileRegion referenceFileRegion = referenceFileChannel.readTilEnd();

		referenceFileRegion.deallocate();
		Assert.assertFalse(referenceFileChannel.isFileChannelClosed());

		referenceFileChannel.close();
		Assert.assertTrue(referenceFileChannel.isFileChannelClosed());

	}

	@Test
	public void testCloseFirst() throws IOException {

		ReferenceFileRegion referenceFileRegion = referenceFileChannel.readTilEnd();
		referenceFileChannel.close();

		Assert.assertFalse(referenceFileChannel.isFileChannelClosed());

		referenceFileRegion.deallocate();
		Assert.assertTrue(referenceFileChannel.isFileChannelClosed());
	}

	@Test
	public void testConcurrentRead() throws InterruptedException, IOException {

		int concurrentCount = 10;
		final LinkedBlockingQueue<ReferenceFileRegion> fileRegions = new LinkedBlockingQueue<>();
		final CountDownLatch latch = new CountDownLatch(concurrentCount);
		

		for (int i = 0; i < concurrentCount; i++) {

			executors.execute(new AbstractExceptionLogTask() {

				@Override
				protected void doRun() throws Exception {
					
					try{
						while (true) {
							
							ReferenceFileRegion referenceFileRegion = referenceFileChannel.readTilEnd(1);
							fileRegions.offer(referenceFileRegion);
							if (referenceFileRegion.count() == 0) {
								break;
							}
						}
					}finally{
						latch.countDown();
					}
				}
			});
		}
		
		latch.await();
		referenceFileChannel.close();
		Assert.assertFalse(referenceFileChannel.isFileChannelClosed());

		long realTotalLen = 0;
		Set<Long> starts = new HashSet<>();
		while (true) {
			
			ReferenceFileRegion referenceFileRegion = fileRegions.poll(100, TimeUnit.MILLISECONDS);
			if(referenceFileRegion == null){
				break;
			}
			
			if(referenceFileRegion.position() != totalFileLen){
				Assert.assertTrue(starts.add(referenceFileRegion.position()));
			}
			
			realTotalLen += referenceFileRegion.count();
			referenceFileRegion.release();
		}

		Assert.assertEquals(totalFileLen, realTotalLen);
		Assert.assertTrue(referenceFileChannel.isFileChannelClosed());

	}

}
