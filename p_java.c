#include <unistd.h>
//#define _GNU_SOURCE 
#include <fcntl.h>  
#include <stdlib.h>
#include <stdio.h>
#include <libgen.h>
#include <string.h>
#include <errno.h>
#include <sys/wait.h>
#include <unistd.h>
#define MAXARGS 50
#define ALLOW_SLOW_DOWN_STREAM 1

int main(int argc, char *argv[]) {

	int pipeA[2], pipeB[2];
	if(pipe(pipeA) == -1||pipe(pipeB) == -1) {
	  perror("Pipe failed");
	  exit(1);
	}

	// get location of this program image.
	char * dname;
	char loc[1024];
	char pathJar[1024];
	char wrapCmd[1024];

	ssize_t lsize=readlink("/proc/self/exe", loc, 1024);
	if (lsize==-1){
		perror("Cannot determine correct path");
		exit(2);
	}
	
	loc[lsize] = 0;
	fprintf(stderr, "Current executable: '%s'", loc);
	dname=dirname(loc);	// dname is same as loc, do not free!
	fprintf(stderr, " dir: '%s'\n", dname);
	
	strcpy(pathJar, dname);
	strcat(pathJar, "/ZIPStream-1.0-SNAPSHOT.jar");

	strcpy(wrapCmd, "/bin/bash ");
	strcat(wrapCmd, dname);
	strcat(wrapCmd, "/wrapper.sh <<INPUTAPK>>");
	
	fprintf(stderr, "Jar: '%s' cmd: '%s'\n\n", pathJar, wrapCmd);

	if (fork()==0){
                // Array of arguments to the wrapped java program.
                // Quick & dirty solution with static boundaries. 
                char * jargs[MAXARGS];
                unsigned int carg = 0;
                unsigned int i;
                memset(jargs, 0x0, sizeof(char*) * MAXARGS);
            
		// Close stdin of the program, duplicate input side of pipe to stdin
		close(0);
		dup2(pipeA[0], 0);
		
		// Close stdout of the program, duplicate output side of pipe to stdout
		// stdout does not need to be redirected, can be directly connected to output.
		// No need to process output of this command thus no need for pipe.
		//close(1);
		//dup2(pipeB[1], 1);
		
		// Closing some shit
		close(pipeA[0]);
		close(pipeA[1]);
		close(pipeB[0]);
		close(pipeB[1]);
	
		// previously used (did not work, now use wrapper):
		// "--cmd", "/bin/bash -c 'cd /root/program/use_jdbc; /bin/java -jar /root/program/use_jdbc/ApkAttack.jar <<INPUTAPK>>'", 
                
                // Arguments are known to be constant, just assign pointer to constant string 
                // in the data section. Better solution: string vector, push-back strings.
                // Want to avoid over-complicating things with c++, vectors, dynamic allocation
                // and so on.
                jargs[carg++] = "/bin/java";
                jargs[carg++] = "-cp";
                jargs[carg++] = pathJar;
                jargs[carg++] = "cz.muni.fi.xklinec.zipstream.Mallory";
                jargs[carg++] = "-f";                           jargs[carg++] = "1";
                jargs[carg++] = "-e";                           jargs[carg++] = ".*gif$";
                jargs[carg++] = "--padd-extra";                 jargs[carg++] = "512000";
                jargs[carg++] = "--recompute-crc32";
                jargs[carg++] = "--create-temp-dir";
                jargs[carg++] = "--omit-missing";
                
                // If APK size was specified. 
                if ((ALLOW_SLOW_DOWN_STREAM) && argc > 1){
                        jargs[carg++] = "--slow-down-stream";
                        jargs[carg++] = "--apk-size",          jargs[carg++] = argv[1];
                }
                
                //jargs[carg++] = "--slow-down-timeout", "75",
                //jargs[carg++] = "--delete-artefacts";
                
                jargs[carg++] = "--cmd";                        jargs[carg++] = wrapCmd;
                jargs[carg++] = NULL;
                
                fprintf(stderr, "Arguments for java application: \n<ARGUMENTS>\n");
                for(i=0; i<carg-1; i++){
                    fprintf(stderr, " %s", jargs[i]);
                }
                
                fprintf(stderr, "\n</ARGUMENTS>\n\n");
                
                // Execute java application.
                errno=0;
		execvp("/bin/java", jargs);
                if (errno){
                        perror("execvp of ./p failed");
                }
                
		exit(1);
	} else {	// parent
		close(pipeA[0]);
		close(pipeB[0]);
		close(pipeB[1]);
		//dup2(pipeA[1], 0);
		//write(pipeA[1], "test", 5);
		
		int readb=0;
		char reading_buf[128];
	        while((readb=read(0, reading_buf, 128)) > 0){
	            write(pipeA[1], reading_buf, readb); // 1 -> stdout
	        }
		
		close(pipeA[0]);
		close(pipeA[1]);
		close(pipeB[0]);
		close(pipeB[1]);

		wait(NULL);
		exit(0);
	}
        
        return 0;
}

