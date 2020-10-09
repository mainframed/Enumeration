/* WREXXX-N-EFFECT                            */
/* All I want to do is zoom-a-zoom-zoom-zoom  */

ADDRESS TSO
SAY '*** Datasets in WARNING mode'
call sayit("SR ALL WARNING NOMASK")
SAY '*** READ or greater datasets'
call sayit("SR FILTER(**)")
say '*** Unix Privileged resources'
call sayit("SR CLASS(UNIXPRIV)")
say '*** BPX Access'
call sayit("SEARCH CLASS(FACILITY) FILTER(BPX.**)")
say '*** Surrogate Access'
call sayit("SEARCH CLASS(SURROGAT) FILTER(*.SUBMIT)")
return 0

sayit:
parse arg cmd
rump = OUTTRAP('shaker.')
ADDRESS TSO CMD
DO i = 1 to shaker.0
  SAY shaker.i
END
RETURN
